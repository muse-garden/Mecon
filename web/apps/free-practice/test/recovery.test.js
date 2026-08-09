import assert from "node:assert/strict";
import test from "node:test";
import { createRecoveryWriter, shouldSaveRecovery } from "../src/recovery.js";

test("recovery follows the session's documentChanged, not the effect kind", () => {
  assert.equal(shouldSaveRecovery({ practiceUpdate: { documentChanged: false } }), false);
  assert.equal(shouldSaveRecovery({ practiceUpdate: { documentChanged: true } }), true);
  // An unsolvable writing scope commits the document and still reports INVALID.
  assert.equal(shouldSaveRecovery({
    practiceUpdate: { effect: { kind: "INVALID" }, documentChanged: true },
  }), true);
  assert.equal(shouldSaveRecovery({
    practiceUpdate: { effect: { kind: "APPLIED" }, documentChanged: false },
  }), false);
  assert.equal(shouldSaveRecovery({ update: { scoreChanged: false } }), false);
  assert.equal(shouldSaveRecovery({ update: { scoreChanged: true } }), true);
});

test("recovery writer debounces persistent frames with newest-wins generation", async () => {
  const callbacks = new Map();
  const cleared = [];
  const writes = [];
  let nextTimer = 0;
  const writer = createRecoveryWriter({
    write: async (generation) => writes.push(generation),
    setTimer: (callback) => {
      const id = ++nextTimer;
      callbacks.set(id, callback);
      return id;
    },
    clearTimer: (id) => cleared.push(id),
  });
  assert.equal(writer.schedule({ practiceUpdate: { documentChanged: true } }), true);
  assert.equal(writer.schedule({ practiceUpdate: { documentChanged: false } }), false);
  assert.equal(writer.schedule({ practiceUpdate: { documentChanged: true } }), true);
  assert.deepEqual(cleared, [1]);
  await callbacks.get(1)();
  await callbacks.get(2)();
  assert.deepEqual(writes, [2]);
  writer.cancel();
});

test("recovery writer serializes an elapsed newer request behind an active write", async () => {
  const callbacks = [];
  const writes = [];
  let releaseFirst;
  const firstWrite = new Promise((resolve) => { releaseFirst = resolve; });
  const writer = createRecoveryWriter({
    write: async (generation) => {
      writes.push(generation);
      if (generation === 1) await firstWrite;
    },
    setTimer: (callback) => {
      callbacks.push(callback);
      return callbacks.length;
    },
    clearTimer: () => {},
  });
  writer.schedule({ practiceUpdate: { documentChanged: true } });
  callbacks[0]();
  await Promise.resolve();
  writer.schedule({ practiceUpdate: { documentChanged: true } });
  callbacks[1]();
  assert.deepEqual(writes, [1]);
  releaseFirst();
  await new Promise((resolve) => setTimeout(resolve, 0));
  assert.deepEqual(writes, [1, 2]);
  writer.cancel();
});
