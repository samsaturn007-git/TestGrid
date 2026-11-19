import path from 'node:path';
import { z } from 'zod';
import type { AppConfig } from './app.ts';

const Env = z.object({
  DEMO_SHOP_HOST: z.string().default('0.0.0.0'),
  DEMO_SHOP_PORT: z.coerce.number().int().min(1).max(65535).default(4173),
  // Exact values only: a typo must fail startup, not silently enable fixtures.
  DEMO_SHOP_FIXTURES: z.enum(['enabled', 'disabled']).default('disabled'),
  DEMO_SHOP_CLIENT_DIR: z.string().optional(),
  DEMO_SHOP_FLAKE_DELAY_MS: z.coerce.number().int().min(0).max(60_000).default(8_000),
  DEMO_SHOP_ORDER_HANG_MS: z.coerce.number().int().min(0).max(600_000).default(120_000),
});

export interface ServerConfig {
  host: string;
  port: number;
  app: AppConfig;
}

export function loadConfig(env: NodeJS.ProcessEnv, defaultClientDir: string): ServerConfig {
  const parsed = Env.parse(env);
  return {
    host: parsed.DEMO_SHOP_HOST,
    port: parsed.DEMO_SHOP_PORT,
    app: {
      fixturesEnabled: parsed.DEMO_SHOP_FIXTURES === 'enabled',
      clientDir: path.resolve(parsed.DEMO_SHOP_CLIENT_DIR ?? defaultClientDir),
      flakeDelayMs: parsed.DEMO_SHOP_FLAKE_DELAY_MS,
      orderHangMs: parsed.DEMO_SHOP_ORDER_HANG_MS,
    },
  };
}
