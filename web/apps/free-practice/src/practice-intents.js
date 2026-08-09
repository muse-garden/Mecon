/**
 * Serial dispatch queue for free-practice intents.
 *
 * The session validates `expectedRevision`, so exactly one intent may be in flight at a time and
 * the next one can only be stamped once the answering frame is back. That makes releasing the
 * in-flight marker a correctness concern rather than bookkeeping: any worker answer that never
 * releases it — an error frame in particular — stops the workbench dead with no visible failure.
 */
export function createPracticeIntentQueue({ send }) {
  const pending = [];
  let inFlight = null;
  let counter = 0;

  function stamp(intent, update) {
    return intent.type === "score"
      ? {
        ...intent,
        expectedRevision: update.revision,
        inner: { ...intent.inner, expectedRevision: update.score.revision },
      }
      : { ...intent, expectedRevision: update.revision };
  }

  function pump(update) {
    // A running solve owns the session's revision; queued intents wait for its result frame.
    if (!update || update.writing?.phase === "RUNNING" || inFlight != null) return;
    const queued = pending.shift();
    if (!queued) return;
    inFlight = queued.clientRequestId;
    send({ clientRequestId: queued.clientRequestId, intent: stamp(queued.intent, update) });
  }

  return {
    /** Queues an intent and sends it when the channel is free. */
    push(intent, update) {
      pending.push({ clientRequestId: ++counter, intent });
      pump(update);
    },
    /**
     * Records a worker answer — a frame or an error — and sends the next queued intent.
     * `clientRequestId` may be null for unsolicited frames such as background results.
     */
    settle(clientRequestId, update) {
      if (clientRequestId != null && clientRequestId === inFlight) inFlight = null;
      pump(update);
    },
    /** Drops everything queued; used when a different document is opened. */
    reset() {
      pending.length = 0;
      inFlight = null;
    },
    get inFlightRequestId() {
      return inFlight;
    },
  };
}
