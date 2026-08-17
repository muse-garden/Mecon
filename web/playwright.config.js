import { defineConfig } from "@playwright/test";

const usesExternalServer = process.env.MECON_E2E_EXTERNAL_SERVER === "1";
const e2ePort = process.env.MECON_E2E_PORT ?? "4173";
const e2eBaseUrl = `http://127.0.0.1:${e2ePort}`;

export default defineConfig({
  testDir: "./apps/free-practice/e2e",
  timeout: 90_000,
  fullyParallel: false,
  workers: 1,
  reporter: "line",
  use: {
    baseURL: e2eBaseUrl,
    // Default to bundled Chromium so the gate runs on any platform; set MECON_E2E_CHANNEL=msedge
    // (or chrome) to exercise an installed browser channel instead.
    ...(process.env.MECON_E2E_CHANNEL ? { channel: process.env.MECON_E2E_CHANNEL } : {}),
    headless: true,
    serviceWorkers: "block",
    trace: "retain-on-failure",
  },
  webServer: usesExternalServer ? undefined : {
    command: `node ./node_modules/vite/bin/vite.js preview apps/free-practice --host 127.0.0.1 --port ${e2ePort}`,
    url: e2eBaseUrl,
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
