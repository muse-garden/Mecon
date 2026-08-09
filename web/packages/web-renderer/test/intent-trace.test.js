import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { requireEngineModule } from "./engine-module.js";

/**
 * JS half of the cross-platform contract: replays the very same checked-in trace that
 * features/score-editing/src/jvmTest/.../SharedIntentTraceTest.kt replays on the JVM, and compares
 * against the same expectations. Regenerate the fixture from the JVM side only.
 */
const fixtureUrl = new URL(
  "../../../../features/score-editing/testdata/intent-trace.json",
  import.meta.url,
);

const GENERATED_ID = /^[0-9a-z]{9}$/;

/** Mirrors SharedIntentTrace.IdNormalizer: ordinals assigned in key-sorted traversal order. */
function createIdNormalizer() {
  const byActual = new Map();
  const byOrdinal = new Map();
  function normalize(value) {
    if (Array.isArray(value)) return value.map(normalize);
    if (value && typeof value === "object") {
      const result = {};
      for (const key of Object.keys(value).sort()) result[key] = normalize(value[key]);
      return result;
    }
    if (typeof value === "string" && GENERATED_ID.test(value)) {
      if (!byActual.has(value)) {
        const ordinal = `@id:${byActual.size}`;
        byActual.set(value, ordinal);
        byOrdinal.set(ordinal, value);
      }
      return byActual.get(value);
    }
    return value;
  }
  return { normalize, actualFor: (ordinal) => byOrdinal.get(ordinal) };
}

/** Mirrors SharedIntentTrace.resolvePlaceholders. */
function resolvePlaceholders(value, latest, ids) {
  if (Array.isArray(value)) return value.map((item) => resolvePlaceholders(item, latest, ids));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, resolvePlaceholders(item, latest, ids)]),
    );
  }
  if (typeof value !== "string") return value;
  if (value.startsWith("@id:")) {
    const actual = ids.actualFor(value);
    assert.ok(actual, `Unknown normalized id placeholder: ${value}`);
    return actual;
  }
  if (value.startsWith("@sel:")) {
    const [index, field] = value.slice("@sel:".length).split(".");
    const target = latest.selection?.[Number(index)];
    assert.ok(target, `Placeholder ${value} has no matching selection entry`);
    assert.ok(target[field] != null, `Placeholder ${value} is missing field ${field}`);
    return target[field];
  }
  if (value.startsWith("@event:")) {
    const separator = value.lastIndexOf(":");
    const voice = value.slice("@event:".length, separator);
    const index = Number(value.slice(separator + 1));
    const events = latest.score?.voiceTracks?.[voice]?.events;
    assert.ok(events, `Placeholder ${value} has no matching voice track`);
    assert.ok(events[index], `Placeholder ${value} is out of range`);
    return events[index].id;
  }
  return value;
}

/** Mirrors SharedIntentTrace.deepEquals / describeDifference. */
function difference(expected, actual, path = "") {
  const expectedIsObject = expected && typeof expected === "object" && !Array.isArray(expected);
  const actualIsObject = actual && typeof actual === "object" && !Array.isArray(actual);
  if (expectedIsObject && actualIsObject) {
    const expectedKeys = Object.keys(expected).sort();
    const actualKeys = Object.keys(actual).sort();
    for (const key of expectedKeys) {
      if (!actualKeys.includes(key)) return `${path}/${key} is missing`;
    }
    for (const key of actualKeys) {
      if (!expectedKeys.includes(key)) return `${path}/${key} is unexpected`;
    }
    for (const key of expectedKeys) {
      const nested = difference(expected[key], actual[key], `${path}/${key}`);
      if (nested) return nested;
    }
    return null;
  }
  if (Array.isArray(expected) && Array.isArray(actual)) {
    if (expected.length !== actual.length) {
      return `${path} has ${actual.length} entries, expected ${expected.length}`;
    }
    for (let index = 0; index < expected.length; index++) {
      const nested = difference(expected[index], actual[index], `${path}[${index}]`);
      if (nested) return nested;
    }
    return null;
  }
  if (typeof expected === "number" && typeof actual === "number") {
    return expected === actual ? null : `${path} expected ${expected} but was ${actual}`;
  }
  if (expected === actual) return null;
  return `${path} expected ${JSON.stringify(expected)} but was ${JSON.stringify(actual)}`;
}

test("shared intent trace produces identical results on Kotlin/JS", async () => {
  const { MeconScoreEditor } = await import(await requireEngineModule());
  const fixture = JSON.parse(readFileSync(fixtureUrl, "utf8"));
  const editor = new MeconScoreEditor(JSON.stringify(fixture.score));
  const ids = createIdNormalizer();

  let latest = JSON.parse(editor.initialUpdateJson());
  ids.normalize(latest);

  const failures = [];
  for (const step of fixture.steps) {
    const intent = resolvePlaceholders(step.intent, latest, ids);
    latest = JSON.parse(editor.dispatchJson(JSON.stringify(intent)));
    const actual = ids.normalize(latest);
    const mismatch = difference(step.expect, actual);
    if (mismatch) failures.push(`${step.name}: ${mismatch}`);
  }
  editor.close();

  assert.deepEqual(
    failures,
    [],
    `Kotlin/JS diverged from the checked-in JVM trace:\n${failures.join("\n")}`,
  );
});
