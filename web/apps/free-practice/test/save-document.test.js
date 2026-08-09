import assert from "node:assert/strict";
import test from "node:test";
import { saveMeconDocument } from "../src/save-document.js";

test("confirmed file-system save reports success only after write and close", async () => {
  const calls = [];
  const result = await saveMeconDocument(new Uint8Array([1, 2, 3]), {
    showSaveFilePicker: async () => ({
      createWritable: async () => ({
        write: async () => calls.push("write"),
        close: async () => calls.push("close"),
      }),
    }),
  });
  assert.equal(result, "saved");
  assert.deepEqual(calls, ["write", "close"]);
});

test("closing the save picker reports cancellation without downloading", async () => {
  let downloaded = false;
  const result = await saveMeconDocument(new Uint8Array([1]), {
    showSaveFilePicker: async () => { throw new DOMException("cancelled", "AbortError"); },
    triggerDownload: () => { downloaded = true; },
  });
  assert.equal(result, "cancelled");
  assert.equal(downloaded, false);
});

test("unsupported browsers download without claiming the document was saved", async () => {
  let downloaded = false;
  const result = await saveMeconDocument(new Uint8Array([1]), {
    showSaveFilePicker: null,
    triggerDownload: () => { downloaded = true; },
  });
  assert.equal(result, "downloaded");
  assert.equal(downloaded, true);
});
