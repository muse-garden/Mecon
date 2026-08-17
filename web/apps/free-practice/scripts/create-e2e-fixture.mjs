import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { writeMeconArchive } from "../../../packages/frozen-score/index.js";
import {
  createMeconFreePractice,
  createMeconFreePracticePreset,
} from "../../../packages/web-renderer/index.js";

function addFractions(left, right) {
  const numerator = left.numerator * right.denominator + right.numerator * left.denominator;
  const denominator = left.denominator * right.denominator;
  const gcd = (a, b) => (b ? gcd(b, a % b) : Math.abs(a));
  const divisor = gcd(numerator, denominator) || 1;
  return { numerator: numerator / divisor, denominator: denominator / divisor };
}

function workspaceEnd(update) {
  const slot = update.document.workspace.slots.at(-1);
  return addFractions(slot.onset, slot.duration);
}

const output = resolve(process.argv[2] ?? "build/e2e/free-practice.mecon");
const score = {
  id: "e2e-score",
  metadata: { title: "Web E2E", createdAt: 0, modifiedAt: 0 },
  measures: [{ number: 1 }, { number: 2 }],
  pitchTracks: {
    pitch: { id: "pitch", events: [] },
    bassPitch: { id: "bassPitch", events: [] },
  },
  voiceTracks: {
    voice: { id: "voice", pitchTrackId: "pitch", voiceNumber: 1, events: [] },
    bassVoice: { id: "bassVoice", pitchTrackId: "bassPitch", voiceNumber: 1, events: [] },
  },
  staffTracks: {
    staff: { id: "staff", name: "Piano RH", voiceTrackIds: ["voice"] },
    bassStaff: { id: "bassStaff", name: "Piano LH", voiceTrackIds: ["bassVoice"] },
  },
  instruments: [{ id: "instrument", name: "Piano", staffIds: ["staff", "bassStaff"] }],
  staffGroups: [{ id: "group", members: [
    { type: "staff", staffId: "staff" }, { type: "staff", staffId: "bassStaff" },
  ] }],
};
const inactiveScore = { ...score, id: "inactive-score", metadata: { ...score.metadata, title: "Inactive" } };
const manifest = {
  formatVersion: 1,
  engineVersion: "browser-e2e",
  createdAt: 0,
  modifiedAt: 0,
  activeScoreId: score.id,
  scores: [
    { id: score.id, title: "Web E2E", path: `scores/${score.id}.json` },
    { id: inactiveScore.id, title: "Inactive", path: `scores/${inactiveScore.id}.json` },
  ],
  modules: [{ id: "future", type: "future.module", schemaVersion: 9, path: "modules/future.json" }],
  workspace: { activeModuleId: "future", selectedScoreIds: [score.id] },
};
const bytes = writeMeconArchive(new Map([
  ["manifest.json", JSON.stringify(manifest)],
  [`scores/${score.id}.json`, JSON.stringify(score)],
  [`scores/${inactiveScore.id}.json`, JSON.stringify(inactiveScore)],
  ["modules/future.json", JSON.stringify({
    id: "future", type: "future.module", schemaVersion: 9,
    payload: { futureField: "preserved", nested: { version: 9 } },
  })],
]));

mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, bytes);
console.log(output);

const practiceOutput = resolve(dirname(output), "free-practice-f1.mecon");
const preset = await createMeconFreePracticePreset();
const practiceModulePath = `modules/${preset.module.id}.json`;
const practiceSibling = {
  ...preset.score,
  id: "free-practice-sibling",
  metadata: { ...preset.score.metadata, title: "Free writing sibling" },
};
const practiceManifest = {
  formatVersion: 1,
  engineVersion: "free-practice-f1",
  createdAt: 0,
  modifiedAt: 0,
  activeScoreId: preset.score.id,
  scores: [
    { id: preset.score.id, title: "Free writing", path: `scores/${preset.score.id}.json` },
    { id: practiceSibling.id, title: practiceSibling.metadata.title, path: `scores/${practiceSibling.id}.json` },
  ],
  modules: [
    {
      id: preset.module.id,
      type: preset.module.type,
      schemaVersion: preset.module.schemaVersion,
      scoreId: preset.score.id,
      path: practiceModulePath,
    },
    { id: "future-practice", type: "future.practice", schemaVersion: 17, path: "modules/future-practice.json" },
  ],
  workspace: { activeModuleId: preset.module.id, selectedScoreIds: [preset.score.id] },
};
const practiceBytes = writeMeconArchive(new Map([
  ["manifest.json", JSON.stringify(practiceManifest)],
  [`scores/${preset.score.id}.json`, JSON.stringify(preset.score)],
  [`scores/${practiceSibling.id}.json`, JSON.stringify(practiceSibling)],
  [practiceModulePath, JSON.stringify({
    id: preset.module.id,
    type: preset.module.type,
    schemaVersion: preset.module.schemaVersion,
    scoreId: preset.score.id,
    payload: preset.document,
  })],
  ["modules/future-practice.json", JSON.stringify({
    id: "future-practice",
    type: "future.practice",
    schemaVersion: 17,
    payload: { futureField: "free-practice-preserved", nested: { version: 17 } },
  })],
]));
writeFileSync(practiceOutput, practiceBytes);
console.log(practiceOutput);

const longPracticeOutput = resolve(dirname(output), "free-practice-f1-64.mecon");
const longSession = await createMeconFreePractice({ document: preset.document, score: preset.score });
let longUpdate = longSession.initialUpdate();
longUpdate = longSession.dispatch({
  type: "updateWritingSettings",
  expectedRevision: longUpdate.revision,
  settings: { ...longUpdate.document.settings.writing, autoWritingEnabled: false },
});
// Add and populate the first rewrite window before extending the stress fixture. Timeline commits
// synchronize the nested score session, so inserting these notes after all 64 geometry commits can
// leave the fixture's inner score revision stale and silently exercise an empty score instead.
while (longUpdate.timeline.slots.length < 3) {
  longUpdate = longSession.dispatch({
    type: "insertChordRange",
    expectedRevision: longUpdate.revision,
    onset: workspaceEnd(longUpdate),
    duration: { numerator: 1, denominator: 4 },
  });
}
const rewriteChoice = longUpdate.document.workspace.slots[0].chordChoice;
for (const slot of longUpdate.document.workspace.slots.slice(1, 3)) {
  longUpdate = longSession.dispatch({
    type: "replaceChord",
    expectedRevision: longUpdate.revision,
    slotId: slot.id,
    chordChoice: rewriteChoice,
  });
}
for (const [numerator, pitch] of [[2, 0], [3, 1], [4, 2]]) {
  longUpdate = longSession.dispatch({
    type: "score",
    expectedRevision: longUpdate.revision,
    inner: {
      type: "insertNote",
      expectedRevision: longUpdate.score.revision,
      voiceTrackId: longUpdate.document.workspace.voices[0].id,
      start: { measure: 1, beat: { numerator, denominator: 8 } },
      duration: { base: "EIGHTH" },
      pitch: { diatonicSteps: pitch },
    },
  });
}
while (longUpdate.timeline.slots.length < 64) {
  longUpdate = longSession.dispatch({
    type: "insertChordRange",
    expectedRevision: longUpdate.revision,
    onset: workspaceEnd(longUpdate),
    duration: { numerator: 1, denominator: 4 },
  });
}
longSession.close();
const longScore = longUpdate.score.score;
const longManifest = {
  ...practiceManifest,
  engineVersion: "free-practice-f1-64",
  scores: [{ id: longScore.id, title: "Free writing 64", path: `scores/${longScore.id}.json` }],
  modules: [practiceManifest.modules[0]],
  workspace: { activeModuleId: preset.module.id, selectedScoreIds: [longScore.id] },
};
const longPracticeBytes = writeMeconArchive(new Map([
  ["manifest.json", JSON.stringify(longManifest)],
  [`scores/${longScore.id}.json`, JSON.stringify(longScore)],
  [practiceModulePath, JSON.stringify({
    id: preset.module.id,
    type: preset.module.type,
    schemaVersion: preset.module.schemaVersion,
    scoreId: longScore.id,
    payload: longUpdate.document,
  })],
]));
writeFileSync(longPracticeOutput, longPracticeBytes);
console.log(longPracticeOutput);
