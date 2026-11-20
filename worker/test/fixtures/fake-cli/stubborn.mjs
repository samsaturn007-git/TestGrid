// Test double for the Playwright CLI: starts a grandchild in the same process group, ignores
// SIGTERM, and prints its env so the test can check what the child received.
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';

const [mode, pidFile, envFile] = process.argv.slice(2);
writeFileSync(envFile, JSON.stringify(process.env));
const grandchild = spawn(process.execPath, ['-e', 'process.on("SIGTERM",()=>{});setInterval(()=>{},1000)'], {
  stdio: 'ignore',
});
writeFileSync(pidFile, String(grandchild.pid));
if (mode === 'ignore-term') process.on('SIGTERM', () => {});
if (mode === 'exit-leaving-orphan') setTimeout(() => process.exit(3), 200);
else setInterval(() => {}, 1000);
