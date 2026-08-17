import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const requested = process.argv.slice(2);
const soak = requested.includes("--soak");
const port = process.env.MECON_E2E_PORT ?? "4173";
const playwrightArgs = soak
  ? ["test", "apps/free-practice/e2e/resource-stability.spec.js", "--reporter=line"]
  : ["test", ...requested];
const environment = {
  ...process.env,
  MECON_E2E_EXTERNAL_SERVER: "1",
  ...(soak ? { MECON_SOAK_MINUTES: process.env.MECON_SOAK_MINUTES ?? "30" } : {}),
};
const server = spawn(process.execPath, [
  resolve(root, "node_modules/vite/bin/vite.js"),
  "preview", "apps/free-practice", "--host", "127.0.0.1", "--port", port,
], { cwd: root, env: environment, stdio: "inherit", windowsHide: true });

async function waitForServer() {
  const deadline = Date.now() + 120_000;
  while (Date.now() < deadline) {
    if (server.exitCode != null) throw new Error(`Vite exited with code ${server.exitCode}`);
    try {
      const response = await fetch(`http://127.0.0.1:${port}/`);
      if (response.ok) return;
    } catch {
      // The preview server is still starting.
    }
    await new Promise((resolveDelay) => setTimeout(resolveDelay, 200));
  }
  throw new Error("Timed out waiting for the Vite preview server");
}

function runPlaywright() {
  return new Promise((resolveRun, rejectRun) => {
    const child = spawn(process.execPath, [
      resolve(root, "node_modules/@playwright/test/cli.js"), ...playwrightArgs,
    ], { cwd: root, env: environment, stdio: "inherit", windowsHide: true });
    child.on("error", rejectRun);
    child.on("exit", (code, signal) => resolveRun(code ?? (signal ? 1 : 0)));
  });
}

let exitCode = 1;
try {
  await waitForServer();
  exitCode = await runPlaywright();
} finally {
  server.kill();
}
process.exitCode = exitCode;
