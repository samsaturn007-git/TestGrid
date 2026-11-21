import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Writable } from 'node:stream';
import { setTimeout as sleep } from 'node:timers/promises';
import { describe, it } from 'node:test';
import type { ConsumeMessage, Options } from 'amqplib';
import { runConsumer, Slots } from '../src/consumer.ts';
import type { Channel, Connection } from '../src/consumer.ts';
import { createLogger } from '../src/logger.ts';
import type { Decision } from '../src/worker.ts';

// Unit tests of the AMQP glue against an in-memory channel (mocked broker). The broker's own
// behaviour (TTL, CC routing, delivery limit, dead-lettering) is tested against a real RabbitMQ
// in the API's TopologyTest.

interface Published {
  exchange: string;
  routingKey: string;
  content: Buffer;
  options: Options.Publish;
}

class FakeChannel extends EventEmitter {
  prefetched: number[] = [];
  consumers = new Map<string, (msg: ConsumeMessage | null) => void>();
  cancelled: string[] = [];
  acks: string[] = [];
  nacks: Array<{ id: string; requeue: boolean }> = [];
  published: Published[] = [];
  /** Order of close calls across channel and connection, to check the channel goes first. */
  closes: string[] = [];
  confirm: (p: Published) => Error | null = () => null;

  prefetch(n: number) {
    this.prefetched.push(n);
    return Promise.resolve({});
  }
  consume(queue: string, cb: (msg: ConsumeMessage | null) => void, opts: Options.Consume) {
    assert.equal(opts.noAck, false, 'manual acks');
    this.consumers.set(queue, cb);
    return Promise.resolve({ consumerTag: `tag-${queue}` });
  }
  cancel(tag: string) {
    this.cancelled.push(tag);
    return Promise.resolve({ consumerTag: tag });
  }
  ack(msg: ConsumeMessage) {
    this.acks.push(String(msg.properties.messageId));
  }
  nack(msg: ConsumeMessage, _all?: boolean, requeue?: boolean) {
    this.nacks.push({ id: String(msg.properties.messageId), requeue: requeue ?? true });
  }
  publish(
    exchange: string,
    routingKey: string,
    content: Buffer,
    options: Options.Publish,
    cb: (err: unknown) => void,
  ) {
    const p = { exchange, routingKey, content, options };
    this.published.push(p);
    setImmediate(() => cb(this.confirm(p)));
    return true;
  }
  close() {
    this.closes.push('channel');
    return Promise.resolve();
  }
  deliver(queue: string, id: string, headers: Record<string, unknown> = {}) {
    const msg = {
      content: Buffer.from(`{"id":"${id}"}`),
      fields: { deliveryTag: 1, redelivered: false, exchange: 'tg.dispatch', routingKey: '' },
      properties: {
        messageId: id,
        contentType: 'application/json',
        type: 'TARGET_DISPATCHED',
        headers: { traceparent: 'tp', ...headers },
      },
    } as unknown as ConsumeMessage;
    const cb = this.consumers.get(queue);
    assert.ok(cb, `no consumer on ${queue}`);
    cb(msg);
  }
}

class FakeConnection extends EventEmitter {
  closed = false;
  readonly channel: FakeChannel;
  constructor(channel: FakeChannel) {
    super();
    this.channel = channel;
  }
  createConfirmChannel() {
    return Promise.resolve(this.channel as unknown as Channel);
  }
  close() {
    this.closed = true;
    this.channel.closes.push('connection');
    return Promise.resolve();
  }
  drop() {
    this.channel.emit('close');
    this.emit('close');
  }
}

const quiet = () => createLogger([], {}, new Writable({ write: (_c, _e, done) => done() }));

async function until(check: () => boolean, ms = 2000) {
  const end = Date.now() + ms;
  while (!check()) {
    if (Date.now() > end) throw new Error('condition not reached');
    await sleep(5);
  }
}

function start(opts: {
  handle: (body: Buffer) => Promise<Decision>;
  slots?: number;
  engines?: string[];
  connections?: FakeConnection[];
  connectFailures?: number;
}) {
  const connections = opts.connections ?? [new FakeConnection(new FakeChannel())];
  const stopping = new AbortController();
  let connects = 0;
  let failures = opts.connectFailures ?? 0;
  const done = runConsumer({
    connect: () => {
      if (failures-- > 0) return Promise.reject(new Error('ECONNREFUSED'));
      const c = connections[Math.min(connects++, connections.length - 1)];
      assert.ok(c);
      return Promise.resolve(c as unknown as Connection);
    },
    engines: opts.engines ?? ['chromium'],
    slots: opts.slots ?? 1,
    log: quiet(),
    stopping: stopping.signal,
    handle: (body) => opts.handle(body),
    reconnectMaxMs: 20,
    random: () => 0.5,
  });
  return { done, stop: () => stopping.abort(), connects: () => connects, connections };
}

describe('runConsumer against an in-memory broker', () => {
  it('sets prefetch = slots and consumes each engine queue with manual acks', async () => {
    const c = start({
      handle: () => Promise.resolve({ action: 'ack', reason: 't' }),
      slots: 2,
      engines: ['chromium', 'firefox'],
    });
    const ch = c.connections[0]!.channel;
    await until(() => ch.consumers.size === 2);
    assert.deepEqual(ch.prefetched, [2]);
    assert.deepEqual([...ch.consumers.keys()], ['tg.targets.chromium', 'tg.targets.firefox']);
    c.stop();
    await c.done;
    assert.deepEqual(ch.cancelled.sort(), ['tag-tg.targets.chromium', 'tag-tg.targets.firefox']);
    assert.ok(c.connections[0]!.closed);
  });

  it('acks, dead-letters without requeue, and passes the traceparent header', async () => {
    const decisions: Record<string, Decision> = {
      a: { action: 'ack', reason: 'done' },
      d: { action: 'dead-letter', reason: 'bad' },
    };
    const c = start({
      handle: (b) => Promise.resolve(decisions[(JSON.parse(b.toString()) as { id: string }).id]!),
    });
    const ch = c.connections[0]!.channel;
    await until(() => ch.consumers.size === 1);
    ch.deliver('tg.targets.chromium', 'a');
    ch.deliver('tg.targets.chromium', 'd');
    await until(() => ch.acks.length + ch.nacks.length === 2);
    assert.deepEqual(ch.acks, ['a']);
    assert.deepEqual(ch.nacks, [{ id: 'd', requeue: false }]);
    c.stop();
    await c.done;
  });

  it('retry: publishes to the retry queue with TTL, CC and a count, then acks after the confirm', async () => {
    const c = start({ handle: () => Promise.resolve({ action: 'retry', reason: 'capacity' }) });
    const ch = c.connections[0]!.channel;
    await until(() => ch.consumers.size === 1);
    ch.deliver('tg.targets.chromium', 'r1', {
      'x-death': [{ queue: 'tg.targets.retry' }],
      'x-first-death-reason': 'expired',
      'x-tg-run-id': 'run-1',
    });
    await until(() => ch.acks.length === 1);
    const p = ch.published[0]!;
    assert.equal(p.exchange, '');
    assert.equal(p.routingKey, 'tg.targets.retry');
    assert.deepEqual(p.options.CC, ['engine.chromium']);
    assert.equal(p.options.expiration, '1000', '1 s for the first retry (jitter fixed at 0)');
    assert.equal(p.options.persistent, true);
    assert.equal(p.options.messageId, 'r1');
    assert.equal(p.options.type, 'TARGET_DISPATCHED');
    assert.deepEqual(p.options.headers, {
      traceparent: 'tp',
      'x-tg-run-id': 'run-1',
      'x-tg-retries': 1,
    });
    assert.equal(p.content.toString(), '{"id":"r1"}', 'body unchanged');

    ch.deliver('tg.targets.chromium', 'r2', { 'x-tg-retries': 4 });
    await until(() => ch.acks.length === 2);
    assert.equal(ch.published[1]!.options.expiration, '16000');
    assert.equal((ch.published[1]!.options.headers as Record<string, unknown>)['x-tg-retries'], 5);
    c.stop();
    await c.done;
  });

  it('retry: an unconfirmed publish keeps the original (requeue) and does not ack it', async () => {
    const c = start({ handle: () => Promise.resolve({ action: 'retry', reason: 'capacity' }) });
    const ch = c.connections[0]!.channel;
    ch.confirm = () => new Error('nack');
    await until(() => ch.consumers.size === 1);
    ch.deliver('tg.targets.chromium', 'r');
    await until(() => ch.nacks.length === 1, 3000);
    assert.deepEqual(ch.nacks, [{ id: 'r', requeue: true }]);
    assert.deepEqual(ch.acks, []);
    c.stop();
    await c.done;
  });

  it('runs at most `slots` attempts at once', async () => {
    let running = 0;
    let peak = 0;
    const c = start({
      slots: 2,
      engines: ['chromium', 'firefox'],
      handle: async () => {
        peak = Math.max(peak, ++running);
        await sleep(30);
        running--;
        return { action: 'ack', reason: 'done' };
      },
    });
    const ch = c.connections[0]!.channel;
    await until(() => ch.consumers.size === 2);
    for (let i = 0; i < 3; i++) ch.deliver('tg.targets.chromium', `c${i}`);
    for (let i = 0; i < 3; i++) ch.deliver('tg.targets.firefox', `f${i}`);
    await until(() => ch.acks.length === 6);
    assert.equal(peak, 2);
    c.stop();
    await c.done;
  });

  it('on stop: the running attempt finishes and is acked, waiting messages are requeued', async () => {
    let release: () => void = () => {};
    const c = start({
      handle: () =>
        new Promise((resolve) => {
          release = () => resolve({ action: 'ack', reason: 'done' });
        }),
    });
    const ch = c.connections[0]!.channel;
    await until(() => ch.consumers.size === 1);
    ch.deliver('tg.targets.chromium', 'running');
    ch.deliver('tg.targets.chromium', 'waiting');
    await sleep(10);
    c.stop();
    await until(() => ch.nacks.length === 1);
    assert.deepEqual(ch.nacks, [{ id: 'waiting', requeue: true }]);
    assert.equal(c.connections[0]!.closed, false, 'still open for the running attempt');
    release();
    await c.done;
    assert.deepEqual(ch.acks, ['running']);
    // The ack must reach the broker: channel close (waits for close-ok) before the connection.
    assert.deepEqual(ch.closes, ['channel', 'connection']);
  });

  it('reconnects after a lost connection; a finished attempt on the dead channel is not acked', async () => {
    let release: () => void = () => {};
    const first = new FakeConnection(new FakeChannel());
    const second = new FakeConnection(new FakeChannel());
    const c = start({
      connections: [first, second],
      handle: () =>
        new Promise((resolve) => {
          release = () => resolve({ action: 'ack', reason: 'done' });
        }),
    });
    await until(() => first.channel.consumers.size === 1);
    first.channel.deliver('tg.targets.chromium', 'm');
    await sleep(10);
    first.drop();
    await until(() => second.channel.consumers.size === 1);
    release();
    await sleep(20);
    assert.deepEqual(first.channel.acks, [], 'acks are per channel; the broker redelivers');
    assert.equal(c.connects(), 2);
    c.stop();
    await c.done;
  });

  it('keeps retrying while the broker is unreachable', async () => {
    const c = start({
      connectFailures: 3,
      handle: () => Promise.resolve({ action: 'ack', reason: 't' }),
    });
    const ch = c.connections[0]!.channel;
    await until(() => ch.consumers.size === 1);
    c.stop();
    await c.done;
  });
});

describe('Slots', () => {
  it('hands out at most n and gives null to waiters once stopping', async () => {
    const slots = new Slots(1);
    const stop = new AbortController();
    const first = await slots.acquire(stop.signal);
    assert.ok(first);
    const waiting = slots.acquire(stop.signal);
    stop.abort();
    assert.equal(await waiting, null);
    first();
    first(); // releasing twice does not create a second slot
    const again = await slots.acquire(new AbortController().signal);
    assert.ok(again);
  });
});
