import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import test from "node:test";

import {
  RHODY_WASM_OUTPUT_PATH,
  RHODY_WASM_PROJECT_PATH,
  resolveRhodyWasmSource,
  rhodyWasmPlugin,
} from "../rhody-vite.js";

test("Rhody project path resolves its release wasm artifact", () => {
  const project = join("workspace", "rhody");
  assert.equal(resolveRhodyWasmSource(project), join(process.cwd(), project, RHODY_WASM_PROJECT_PATH));
  assert.equal(resolveRhodyWasmSource(""), null);
});

test("Rhody Vite plugin serves the wasm in dev and emits it in production", async () => {
  const root = await mkdtemp(join(tmpdir(), "mecon-rhody-"));
  try {
    const wasmPath = join(root, RHODY_WASM_PROJECT_PATH);
    const wasm = Buffer.from([0x00, 0x61, 0x73, 0x6d]);
    await mkdir(join(wasmPath, ".."), { recursive: true });
    await writeFile(wasmPath, wasm);
    const plugin = rhodyWasmPlugin({ projectPath: root });
    plugin.configResolved({ base: "/demo/", logger: { warn() {} } });

    let middleware;
    plugin.configureServer({ middlewares: { use(handler) { middleware = handler; } } });
    const headers = new Map();
    let body = null;
    middleware(
      { url: `/demo/${RHODY_WASM_OUTPUT_PATH}` },
      {
        setHeader(name, value) { headers.set(name, value); },
        end(value) { body = value; },
      },
      () => assert.fail("the bundled Rhody route must not fall through"),
    );
    assert.equal(headers.get("Content-Type"), "application/wasm");
    assert.deepEqual(body, wasm);

    let emitted;
    plugin.generateBundle.call({ emitFile(asset) { emitted = asset; } });
    assert.equal(emitted.fileName, RHODY_WASM_OUTPUT_PATH);
    assert.deepEqual(emitted.source, wasm);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("missing Rhody project leaves the build dependency-free and fallback-enabled", () => {
  const plugin = rhodyWasmPlugin({ projectPath: join(tmpdir(), "missing-rhody-project") });
  let registered = false;
  plugin.configureServer({ middlewares: { use() { registered = true; } } });
  assert.equal(registered, false);
  let emitted = false;
  plugin.generateBundle.call({ emitFile() { emitted = true; } });
  assert.equal(emitted, false);
});
