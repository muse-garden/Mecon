const DATABASE = "mecon-free-practice";
const STORE = "recovery";
const KEY = "active-document";

/**
 * The session decides what is worth persisting. Classifying effect kinds here duplicated internal
 * semantics and could not express "committed, then reported invalid" — an unsolvable writing scope
 * commits the document and still answers INVALID, so those edits were silently never saved.
 */
export function shouldSaveRecovery(frame) {
  if (frame?.practiceUpdate) return frame.practiceUpdate.documentChanged === true;
  return frame?.update?.scoreChanged === true;
}

/** Newest-wins debounce; selection/catalog/finding frames never rebuild the archive. */
export function createRecoveryWriter({
  write,
  delayMs = 300,
  setTimer = setTimeout,
  clearTimer = clearTimeout,
  onError = () => {},
}) {
  let generation = 0;
  let timer = null;
  let writing = false;
  let pendingGeneration = null;

  async function flush(requestedGeneration) {
    if (requestedGeneration !== generation) return;
    if (writing) {
      pendingGeneration = requestedGeneration;
      return;
    }
    writing = true;
    try {
      await write(requestedGeneration);
    } catch (error) {
      onError(error);
    } finally {
      writing = false;
      const pending = pendingGeneration;
      pendingGeneration = null;
      if (pending != null) await flush(pending);
    }
  }

  return {
    schedule(frame) {
      if (!shouldSaveRecovery(frame)) return false;
      const requestedGeneration = ++generation;
      if (timer != null) clearTimer(timer);
      timer = setTimer(() => {
        timer = null;
        void flush(requestedGeneration);
      }, delayMs);
      return true;
    },
    /** Writes the pending edit immediately; used when the page is going away. */
    async flush() {
      if (timer == null) return;
      clearTimer(timer);
      timer = null;
      await flush(generation);
    },
    cancel() {
      generation += 1;
      if (timer != null) clearTimer(timer);
      timer = null;
      pendingGeneration = null;
    },
  };
}

export async function loadRecoveryState() {
  const database = await openDatabase();
  const stored = await transactionRequest(database, "readonly", (store) => store.get(KEY));
  if (!stored) return null;
  if (stored.bytes instanceof ArrayBuffer) {
    return { bytes: stored.bytes, unsaved: stored.unsaved !== false };
  }
  // Archives written by older versions had no saved-state metadata. Treat them conservatively as
  // unsaved so a recovered edit is never discarded without confirmation.
  return { bytes: stored, unsaved: true };
}

export async function loadRecovery() {
  return (await loadRecoveryState())?.bytes ?? null;
}

export async function saveRecovery(bytes, unsaved = true) {
  const database = await openDatabase();
  const copy = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
  await transactionRequest(database, "readwrite", (store) => store.put({ bytes: copy, unsaved }, KEY));
}

let databasePromise = null;

/**
 * One connection for the whole session. Opening a fresh one per read/write and never closing it
 * accumulates handles across a long session and blocks any future `onupgradeneeded`.
 */
function openDatabase() {
  if (!databasePromise) {
    databasePromise = new Promise((resolve, reject) => {
      const request = indexedDB.open(DATABASE, 1);
      request.onupgradeneeded = () => request.result.createObjectStore(STORE);
      request.onsuccess = () => {
        // A newer tab requesting a version upgrade must not be blocked by this connection.
        request.result.onversionchange = () => {
          request.result.close();
          databasePromise = null;
        };
        resolve(request.result);
      };
      request.onerror = () => reject(request.error);
    }).catch((error) => {
      databasePromise = null;
      throw error;
    });
  }
  return databasePromise;
}

function transactionRequest(database, mode, operation) {
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(STORE, mode);
    const request = operation(transaction.objectStore(STORE));
    request.onsuccess = () => resolve(request.result ?? null);
    request.onerror = () => reject(request.error);
    transaction.onerror = () => reject(transaction.error);
  });
}
