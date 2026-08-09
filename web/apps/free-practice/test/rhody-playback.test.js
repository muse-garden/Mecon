import assert from "node:assert/strict";
import test from "node:test";

import {
  ORGAN_PRESETS,
  PlaybackInstrument,
  resolveRhodyWasmUrl,
} from "../src/rhody-playback.js";

test("Rhody playback defaults expose piano, organ, and the eight stable organ registrations", () => {
  assert.equal(PlaybackInstrument.piano, "piano");
  assert.equal(PlaybackInstrument.organ, "organ");
  assert.equal(ORGAN_PRESETS.length, 8);
  assert.deepEqual(ORGAN_PRESETS[0], { value: 0, label: "Principal Chorus" });
  assert.equal(ORGAN_PRESETS.at(-1).value, 7);
});

test("bundled Rhody playback resolves its Mecon-owned asset URL", () => {
  assert.equal(
    resolveRhodyWasmUrl("/demo/free-practice/"),
    "http://localhost/demo/free-practice/rhody/rhody_wasm.wasm",
  );
});
