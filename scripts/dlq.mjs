// PRD-04 T7 operator tool for tg.targets.dead (docs/RUNBOOK.md §4). It runs in a one-off
// container from the worker image, which already has amqplib and the broker credentials;
// scripts/dlq.sh pipes it in on stdin, so nothing is copied anywhere. DLQ_ACTION=list | replay.
//   list   prints one tab-separated line per message and puts every message back with
//          basic.nack(requeue): on RabbitMQ 4.4 a nack does not count toward a quorum queue's
//          delivery limit, whereas a channel closed with unacked messages would.
//   Both read at most the messages present when they start (checkQueue).
//   replay republishes each message, body and properties unchanged apart from the broker's
//          x-death headers, to the engine queue it was dead-lettered from (x-first-death-queue),
//          waits for the publisher confirm, then acks the dead letter.
import amqp from 'amqplib';

const DEAD = 'tg.targets.dead';
const action = process.env.DLQ_ACTION ?? 'list';
if (!['list', 'replay'].includes(action)) {
  console.error(`unknown DLQ_ACTION ${action}`);
  process.exit(2);
}
const conn = await amqp.connect({
  protocol: 'amqp',
  hostname: process.env.RABBITMQ_HOST ?? 'rabbitmq',
  port: Number(process.env.RABBITMQ_PORT ?? 5672),
  username: process.env.RABBITMQ_USER,
  password: process.env.RABBITMQ_PASSWORD,
});
const ch = await conn.createConfirmChannel();
const held = [];
let replayed = 0;
let skipped = 0;
// Only the messages that are there now. A replayed message can be dead-lettered again while this
// runs (its cause not fixed yet); without a bound the loop would pick it up and replay it forever.
const { messageCount } = await ch.checkQueue(DEAD);
for (let i = 0; i < messageCount; i++) {
  const msg = await ch.get(DEAD, { noAck: false });
  if (!msg) break;
  const h = msg.properties.headers ?? {};
  let body = {};
  try {
    body = JSON.parse(msg.content.toString('utf8'));
  } catch {
    // An unparseable message is listed with empty fields and never replayed.
  }
  const queue = String(h['x-first-death-queue'] ?? '');
  const line = [
    body.targetId ?? '-',
    body.dispatchGeneration ?? '-',
    body.messageId ?? msg.properties.messageId ?? '-',
    queue || '-',
    String(h['x-first-death-reason'] ?? '-'),
  ].join('\t');
  if (action === 'list') {
    console.log(line);
    held.push(msg);
    continue;
  }
  if (!/^tg\.targets\.(chromium|firefox|webkit)$/.test(queue) || !body.targetId) {
    console.log(`skip\t${line}`);
    held.push(msg);
    skipped++;
    continue;
  }
  const headers = Object.fromEntries(
    Object.entries(h).filter(([k]) => !/^x-(death|first-death-|last-death-)/.test(k)),
  );
  await new Promise((resolve, reject) =>
    ch.publish(
      '',
      queue,
      msg.content,
      {
        persistent: true,
        contentType: msg.properties.contentType,
        messageId: msg.properties.messageId,
        type: msg.properties.type,
        headers,
      },
      (err) => (err ? reject(new Error('publish not confirmed')) : resolve()),
    ),
  );
  ch.ack(msg);
  console.log(`replayed\t${line}`);
  replayed++;
}
for (const msg of held) ch.nack(msg, false, true);
// Channel first, then the connection: an ack or nack followed straight by connection.close()
// was lost on RabbitMQ 4.4.1 (docs/API_AND_DATA_CONTRACTS.md §9).
await ch.close();
await conn.close();
if (action === 'replay') console.log(`replayed ${replayed}, skipped ${skipped}`);
