import test from "node:test";
import assert from "node:assert/strict";
import {
  EngineFacade,
  ScoreEditorFacade,
  createEngineFacade,
  createScoreEditorFacade,
} from "../engine-core.js";

const frozen = {
  schemaVersion: 1,
  bounds: { origin: { x: 0, y: 0 }, width: 10, height: 10 },
  surfaces: [{ index: 0, width: 10, height: 10, elements: [] }],
};

test("complete facade keeps Kotlin behind a JSON-only boundary", async () => {
  let received;
  class FakeEngine {
    constructor(metadata, glyphNames, version) {
      assert.equal(metadata, "metadata");
      assert.equal(glyphNames, "glyphs");
      assert.equal(version, "test");
    }
    renderScoreJson(json) {
      received = json;
      return JSON.stringify(frozen);
    }
  }
  const renderer = await createEngineFacade({
    metadataJson: "metadata",
    glyphNamesJson: "glyphs",
    engineVersion: "test",
    engineModule: { MeconWebEngine: FakeEngine },
  });
  assert.ok(renderer instanceof EngineFacade);
  assert.equal(renderer.layout({ id: "score" }).surfaces.length, 1);
  assert.deepEqual(JSON.parse(received), { id: "score" });
});

test("score editor facade keeps intents and updates on the JSON boundary", async () => {
  const received = [];
  class FakeScoreEditor {
    constructor(scoreJson) {
      assert.deepEqual(JSON.parse(scoreJson), { id: "score" });
    }
    initialUpdateJson() {
      return JSON.stringify({ revision: 0, score: { id: "score" } });
    }
    dispatchJson(intentJson) {
      received.push(JSON.parse(intentJson));
      return JSON.stringify({ revision: 1, effect: { kind: "APPLIED" } });
    }
    close() {
      received.push("closed");
    }
  }
  const editor = await createScoreEditorFacade({
    score: { id: "score" },
    engineModule: { MeconScoreEditor: FakeScoreEditor },
  });
  assert.ok(editor instanceof ScoreEditorFacade);
  assert.equal(editor.initialUpdate().revision, 0);
  assert.equal(editor.dispatch({ type: "undo", expectedRevision: 0 }).revision, 1);
  editor.close();
  assert.deepEqual(received, [{ type: "undo", expectedRevision: 0 }, "closed"]);
});
