import { setTimeout as sleep } from 'node:timers/promises';
import type { ChannelModel, ConfirmChannel, ConsumeMessage, Options } from 'amqplib';
import type { Logger } from 'pino';
import { retryDelayMs } from './dispatch-message.ts';
import type { Decision } from './worker.ts';
import { describe } from './worker.ts';

// PRD-04 FR-5, contracts §9: the AMQP side of the worker. Manual acks with prefetch = slots,
// at most `slots` attempts at once, and every decision carried out on the channel the message
// came from:
//   ack          → basic.ack
//   retry        → publish to tg.targets.retry (TTL, CC engine.<engine>), wait for the confirm,
//                  then ack; the broker hands it back to tg.targets.<engine> when the TTL expires
//   dead-letter  → basic.nack without requeue → tg.dlx → tg.targets.dead
// A crash before the ack makes the broker redeliver; that redelivery counts toward the queue's
// x-delivery-limit, and the claim answers 409 if the target has moved on. A nack with requeue
// (used only on shutdown, for messages that never started) does not count on RabbitMQ 4.4.

export const RETRY_QUEUE = 'tg.targets.retry';
export const RETRIES_HEADER = 'x-tg-retries';
/** amqplib types every property as `any`; these are the ones the worker reads or copies. */
interface Props {
  messageId?: string;
  type?: string;
  contentType?: string;
  contentEncoding?: string;
  headers?: Record<string, unknown>;
}
const props = (msg: ConsumeMessage) => msg.properties as Props;

// Set by the broker when it dead-letters; a republished message must not carry them.
const BROKER_HEADERS = /^x-(death|first-death-|last-death-)/;

export type Channel = Pick<
  ConfirmChannel,
  'prefetch' | 'consume' | 'cancel' | 'ack' | 'nack' | 'publish' | 'on' | 'close'
>;
export type Connection = Pick<ChannelModel, 'on' | 'close'> & {
  createConfirmChannel(): Promise<Channel>;
};

export interface ConsumerOptions {
  connect: () => Promise<Connection>;
  engines: readonly string[];
  slots: number;
  log: Logger;
  stopping: AbortSignal;
  handle: (body: Buffer, traceparent: unknown) => Promise<Decision>;
  reconnectMaxMs: number;
  random?: () => number;
}

/** Counts free attempt slots; a waiter gets null instead of a slot once the worker stops. */
export class Slots {
  #free: number;
  readonly #waiters: Array<() => void> = [];

  constructor(count: number) {
    this.#free = count;
  }

  acquire(signal: AbortSignal): Promise<(() => void) | null> {
    if (signal.aborted) return Promise.resolve(null);
    if (this.#free > 0) {
      this.#free--;
      return Promise.resolve(this.#releaser());
    }
    return new Promise((resolve) => {
      const waiter = () => {
        signal.removeEventListener('abort', onAbort);
        resolve(this.#releaser());
      };
      const onAbort = () => {
        const i = this.#waiters.indexOf(waiter);
        if (i >= 0) this.#waiters.splice(i, 1);
        resolve(null);
      };
      this.#waiters.push(waiter);
      signal.addEventListener('abort', onAbort, { once: true });
    });
  }

  #releaser(): () => void {
    let released = false;
    return () => {
      if (released) return;
      released = true;
      const next = this.#waiters.shift();
      if (next) next();
      else this.#free++;
    };
  }
}

export async function runConsumer(opts: ConsumerOptions): Promise<void> {
  const slots = new Slots(opts.slots);
  const inFlight = new Set<Promise<void>>();
  let delay = 500;
  while (!opts.stopping.aborted) {
    let session: Session;
    try {
      session = await openSession(opts, slots, inFlight);
    } catch (e) {
      opts.log.warn({ err: describe(e), retryInMs: delay }, 'broker not reachable; retrying');
      await pause(jitter(delay, opts.random), opts.stopping);
      delay = Math.min(delay * 2, opts.reconnectMaxMs);
      continue;
    }
    delay = 500;
    const why = await session.ended;
    if (why === 'stopping') {
      await session.shutdown(inFlight);
      break;
    }
    opts.log.warn(
      { reason: why },
      'broker connection lost; running attempts continue, unacked messages will be redelivered',
    );
    await session.abandon();
  }
  await Promise.allSettled([...inFlight]);
  opts.log.info('consumer stopped');
}

interface Session {
  ended: Promise<string>;
  /** Stopping: cancel consumers, let current attempts finish and ack, then close. */
  shutdown(inFlight: Set<Promise<void>>): Promise<void>;
  /** Lost: close whatever is left of the connection before opening a new one. */
  abandon(): Promise<void>;
}

async function openSession(
  opts: ConsumerOptions,
  slots: Slots,
  inFlight: Set<Promise<void>>,
): Promise<Session> {
  const connection = await opts.connect();
  // `open` is about the channel (acks are only valid on it); `ended` is about the session.
  let open = true;
  let end: (why: string) => void = () => {};
  const ended = new Promise<string>((resolve) => {
    end = resolve;
  });
  const closed = (why: string) => {
    open = false;
    end(why);
  };
  // 'error' is always followed by 'close'; without a listener an error event would throw.
  connection.on('error', (e: Error) => opts.log.warn({ err: describe(e) }, 'connection error'));
  connection.on('close', () => closed('connection closed'));
  let channel: Channel;
  try {
    channel = await connection.createConfirmChannel();
    channel.on('error', (e: Error) => opts.log.warn({ err: describe(e) }, 'channel error'));
    channel.on('close', () => closed('channel closed'));
    // Per consumer (quorum queues have no global QoS): with one engine this is the slot count;
    // with several, extra deliveries wait for a local slot unstarted.
    await channel.prefetch(opts.slots);
  } catch (e) {
    await connection.close().catch(() => {});
    throw e;
  }
  const isOpen = () => open;
  const onStop = () => end('stopping');
  opts.stopping.addEventListener('abort', onStop, { once: true });

  const tags: string[] = [];
  try {
    for (const engine of opts.engines) {
      const queue = `tg.targets.${engine}`;
      const { consumerTag } = await channel.consume(
        queue,
        (msg) => {
          if (!msg) {
            opts.log.warn({ queue }, 'consumer cancelled by the broker');
            end('consumer cancelled');
            return;
          }
          const task = deliver(opts, channel, isOpen, slots, msg, engine);
          inFlight.add(task);
          void task.finally(() => inFlight.delete(task));
        },
        { noAck: false },
      );
      tags.push(consumerTag);
    }
  } catch (e) {
    opts.stopping.removeEventListener('abort', onStop);
    await connection.close().catch(() => {});
    throw e;
  }
  opts.log.info({ engines: opts.engines, prefetch: opts.slots }, 'consuming dispatch messages');

  return {
    ended,
    async shutdown(running) {
      // No new deliveries; messages already here but not started are requeued by deliver().
      for (const tag of tags) await channel.cancel(tag).catch(() => {});
      opts.log.info({ inFlight: running.size }, 'stopped consuming; finishing current attempts');
      await Promise.allSettled([...running]);
      // Close the channel first and wait for close-ok: on RabbitMQ 4.4.1 an ack followed straight
      // by connection.close() was lost (the message was redelivered after restart); a channel
      // close is answered only after the broker has processed the frames before it.
      await channel.close().catch(() => {});
      await connection.close().catch(() => {});
    },
    async abandon() {
      opts.stopping.removeEventListener('abort', onStop);
      await connection.close().catch(() => {});
    },
  };
}

async function deliver(
  opts: ConsumerOptions,
  channel: Channel,
  isOpen: () => boolean,
  slots: Slots,
  msg: ConsumeMessage,
  engine: string,
): Promise<void> {
  const release = await slots.acquire(opts.stopping);
  if (!release) {
    // Stopping before this message started: give it back without counting a delivery.
    if (isOpen()) channel.nack(msg, false, true);
    return;
  }
  try {
    if (!isOpen()) {
      // The channel died while this waited for a slot; the broker has requeued it already.
      return;
    }
    let decision: Decision;
    try {
      decision = await opts.handle(msg.content, props(msg).headers?.traceparent);
    } catch (e) {
      opts.log.error({ err: describe(e) }, 'delivery handler failed; parking the message');
      decision = { action: 'retry', reason: 'handler error' };
    }
    await settle(opts, channel, isOpen, msg, engine, decision);
  } finally {
    release();
  }
}

async function settle(
  opts: ConsumerOptions,
  channel: Channel,
  isOpen: () => boolean,
  msg: ConsumeMessage,
  engine: string,
  decision: Decision,
): Promise<void> {
  const p = props(msg);
  const log = opts.log.child({ messageId: p.messageId, decision: decision.action });
  if (!isOpen()) {
    // Acks are per channel: this one can't be acked any more. The broker redelivers it and the
    // claim answers 409 if the target has moved on (contracts §9).
    log.warn({ reason: decision.reason }, 'channel gone before the ack; the broker will redeliver');
    return;
  }
  if (decision.action === 'ack') {
    channel.ack(msg);
    return;
  }
  if (decision.action === 'dead-letter') {
    channel.nack(msg, false, false);
    log.warn({ reason: decision.reason }, 'dead-lettered');
    return;
  }
  const headers: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(p.headers ?? {})) {
    if (!BROKER_HEADERS.test(k) && k !== 'CC' && k !== 'BCC') headers[k] = v;
  }
  const retries = (Number(headers[RETRIES_HEADER]) || 0) + 1;
  headers[RETRIES_HEADER] = retries;
  const ttl = retryDelayMs(retries, opts.random);
  const options: Options.Publish = {
    persistent: true,
    headers,
    // Dead-lettering keeps the routing keys, CC included: engine.<engine> routes it home.
    CC: [`engine.${engine}`],
    expiration: String(ttl),
  };
  if (p.contentType !== undefined) options.contentType = p.contentType;
  if (p.contentEncoding !== undefined) options.contentEncoding = p.contentEncoding;
  if (p.messageId !== undefined) options.messageId = p.messageId;
  if (p.type !== undefined) options.type = p.type;
  try {
    await new Promise<void>((resolve, reject) => {
      channel.publish('', RETRY_QUEUE, msg.content, options, (err: unknown) =>
        err ? reject(err instanceof Error ? err : new Error('publish not confirmed')) : resolve(),
      );
    });
  } catch (e) {
    // Not confirmed: keep the original. A requeue doesn't count toward the delivery limit, so
    // wait a moment first instead of spinning on a broker that refuses publishes.
    log.warn({ err: describe(e) }, 'retry publish not confirmed; requeueing the original');
    await sleep(1000);
    if (isOpen()) channel.nack(msg, false, true);
    return;
  }
  // A crash between the confirm and this ack leaves two copies; the claim fences the second.
  if (isOpen()) channel.ack(msg);
  log.info({ reason: decision.reason, retries, ttlMs: ttl }, 'parked in the retry queue');
}

function jitter(ms: number, random: () => number = Math.random): number {
  return Math.round(ms * (0.8 + random() * 0.4));
}

async function pause(ms: number, signal: AbortSignal): Promise<void> {
  try {
    await sleep(ms, undefined, { signal });
  } catch {
    // Woken early by shutdown.
  }
}
