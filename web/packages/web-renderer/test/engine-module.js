import { existsSync } from "node:fs";

const moduleUrl = new URL("../kotlin/mecon-bridge-web-engine.mjs", import.meta.url);

/**
 * These tests are the only mechanism proving the Kotlin/JS bundle behaves like the JVM one, so a
 * missing bundle must fail rather than quietly skip. Set MECON_SKIP_ENGINE_TESTS=1 to opt out
 * deliberately when iterating on the pure-JS packages.
 */
const optedOut = process.env.MECON_SKIP_ENGINE_TESTS === "1";

export const engineSkip = optedOut
  ? "MECON_SKIP_ENGINE_TESTS=1: skipping generated Kotlin/JS engine tests"
  : false;

export async function requireEngineModule() {
  if (!existsSync(moduleUrl)) {
    throw new Error(
      "The generated Kotlin/JS engine is missing. Run `npm run prepare:engine` from web/ " +
        "(or `npm run test:engine`) before running these tests. To skip them on purpose, set " +
        "MECON_SKIP_ENGINE_TESTS=1.",
    );
  }
  return moduleUrl.href;
}
