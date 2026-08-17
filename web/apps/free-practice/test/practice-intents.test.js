import assert from "node:assert/strict";
import test from "node:test";
import { createPracticeIntentQueue } from "../src/practice-intents.js";

const update = (revision, extra = {}) => ({
  revision,
  score: { revision: revision * 10 },
  ...extra,
});

test("one intent is in flight at a time and each is stamped with the current revisions", () => {
  const sent = [];
  const queue = createPracticeIntentQueue({ send: (message) => sent.push(message) });

  queue.push({ type: "selectSlot", slotId: "slot-0" }, update(4));
  queue.push({ type: "score", inner: { type: "undo" } }, update(4));

  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].intent, { type: "selectSlot", slotId: "slot-0", expectedRevision: 4 });

  queue.settle(sent[0].clientRequestId, update(5));
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1].intent, {
    type: "score",
    inner: { type: "undo", expectedRevision: 50 },
    expectedRevision: 5,
  });
});

test("a worker error releases the queue so later edits still reach the session", () => {
  const sent = [];
  const queue = createPracticeIntentQueue({ send: (message) => sent.push(message) });

  queue.push({ type: "selectSlot", slotId: "slot-0" }, update(1));
  const failed = sent[0].clientRequestId;
  // The worker answered with an error rather than a frame; without settling here the workbench
  // would accept no further intent until the page is reloaded.
  queue.settle(failed, update(1));
  assert.equal(queue.inFlightRequestId, null);

  queue.push({ type: "selectSlot", slotId: "slot-1" }, update(1));
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1].intent, { type: "selectSlot", slotId: "slot-1", expectedRevision: 1 });
});

test("a running solve holds the channel and unsolicited frames do not release it", () => {
  const sent = [];
  const queue = createPracticeIntentQueue({ send: (message) => sent.push(message) });

  queue.push({ type: "selectSlot", slotId: "slot-0" }, update(2, { writing: { phase: "RUNNING" } }));
  assert.equal(sent.length, 0, "queued while the solve owns the revision");

  queue.settle(null, update(2, { writing: { phase: "RUNNING" } }));
  assert.equal(sent.length, 0);

  queue.settle(null, update(3, { writing: { phase: "READY" } }));
  assert.equal(sent.length, 1);
  assert.equal(sent[0].intent.expectedRevision, 3);

  // A background-result frame carries no clientRequestId and must not clear the in-flight marker.
  queue.settle(null, update(4));
  assert.equal(sent.length, 1);
  assert.equal(queue.inFlightRequestId, sent[0].clientRequestId);
});

test("cancelWriting preempts ordinary intents queued behind a running solve", () => {
  const sent = [];
  const queue = createPracticeIntentQueue({ send: (message) => sent.push(message) });
  const running = update(7, { writing: { phase: "RUNNING" } });

  queue.push({ type: "selectSlot", slotId: "slot-1" }, running);
  queue.push({ type: "cancelWriting" }, running);

  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].intent, { type: "cancelWriting", expectedRevision: 7 });

  queue.settle(sent[0].clientRequestId, update(8, { writing: { phase: "READY" } }));
  assert.equal(sent.length, 2);
  assert.deepEqual(sent[1].intent, { type: "selectSlot", slotId: "slot-1", expectedRevision: 8 });
});

test("reset drops queued work when another document is opened", () => {
  const sent = [];
  const queue = createPracticeIntentQueue({ send: (message) => sent.push(message) });
  queue.push({ type: "selectSlot", slotId: "slot-0" }, update(1));
  queue.push({ type: "selectSlot", slotId: "slot-1" }, update(1));

  queue.reset();
  queue.settle(1, update(1));

  assert.equal(sent.length, 1);
  assert.equal(queue.inFlightRequestId, null);
});
