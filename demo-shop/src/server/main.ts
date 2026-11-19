import path from 'node:path';
import { createApp } from './app.ts';
import { loadConfig } from './config.ts';

const config = loadConfig(process.env, path.join(import.meta.dirname, '../../dist/client'));
const { app, shutdown } = createApp(config.app);

const server = app.listen(config.port, config.host, () => {
  console.log(
    JSON.stringify({
      msg: 'demo-shop listening',
      host: config.host,
      port: config.port,
      fixtures: config.app.fixturesEnabled ? 'enabled' : 'disabled',
    }),
  );
});

function stop(signal: string): void {
  console.log(JSON.stringify({ msg: 'demo-shop stopping', signal }));
  shutdown();
  server.close(() => process.exit(0));
  server.closeAllConnections();
}
process.on('SIGTERM', () => stop('SIGTERM'));
process.on('SIGINT', () => stop('SIGINT'));
