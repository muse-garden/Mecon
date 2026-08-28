import assert from "node:assert/strict";
import { readdir, readFile } from "node:fs/promises";
import { dirname, extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const sourceRoot = join(dirname(fileURLToPath(import.meta.url)), "../src");
const webRoot = join(sourceRoot, "../../..");
const serviceWorkerPath = join(sourceRoot, "../../../../apps/desktop/src/main/resources/sw.js");

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map((entry) => {
    const path = join(directory, entry.name);
    return entry.isDirectory() ? sourceFiles(path) : [path];
  }));
  return nested.flat().filter((path) => [".js", ".jsx", ".ts", ".tsx"].includes(extname(path)));
}

test("free-practice React shell cannot import or copy kernel business authorities", async () => {
  const forbidden = [
    "HarmonyWorkspaceEditor",
    "ChordSelectionCatalog",
    "StorageScore.create",
    "SchoenbergFreePracticeCatalog",
  ];
  for (const path of await sourceFiles(sourceRoot)) {
    const source = await readFile(path, "utf8");
    for (const token of forbidden) {
      assert.equal(source.includes(token), false, `${path} must not contain ${token}`);
    }
  }
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const worker = await readFile(join(sourceRoot, "engine-worker.js"), "utf8");
  assert.equal(app.includes("createMeconFreePractice("), false, "React must not instantiate the kernel session");
  assert.equal(worker.includes("createMeconFreePractice("), true, "the Worker owns the kernel session");
});

test("free-practice composes the public complete ScoreEditor host", async () => {
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  assert.match(app, /<ScoreEditor\b/);
  assert.match(app, /useScoreEditorController\(/);
  for (const privatePiece of [
    "ScoreEditorSurface",
    "ScoreEditorToolbar",
    "createScoreEditorSelectionController",
    "createScoreEditorDragController",
    "createScoreEditorCommandController",
  ]) {
    assert.equal(
      app.includes(privatePiece),
      false,
      `the app must obtain ${privatePiece} through the public ScoreEditor host`,
    );
  }
});

test("right panel replays the shared plan projection without duplicating top-toolbar controls", async () => {
  const plan = await readFile(join(sourceRoot, "PracticePlanPanel.jsx"), "utf8");
  for (const duplicatedControl of [
    "练习设置", "写作设置", "<h3>配声</h3>", "onRunWriting", "onRebuildPractice",
    "onUpdateWritingSettings", "onPlayback", "onCancelWriting",
  ]) {
    assert.equal(plan.includes(duplicatedControl), false,
      `right panel must not duplicate top-toolbar control ${duplicatedControl}`);
  }
  for (const platformDerivation of [
    "update.timeline?.slots?.find", "activeTonalLayoutIds.includes", "update.timeline?.idioms",
  ]) {
    assert.equal(plan.includes(platformDerivation), false,
      `right panel must consume presentation-ready plan state instead of ${platformDerivation}`);
  }
  assert.match(plan, /plan\.activeTonalLayouts/);
  assert.match(plan, /plan\.navigation/);
  assert.match(plan, /plan\.coveredIdioms/);
  assert.match(plan, /plan\.tonalityChoices/);
  assert.match(plan, /plan\.bassChoices/);
  assert.match(plan, /plan\.chordCatalogGroups/);
  assert.match(plan, /choice\.displayLabel/);
  assert.match(plan, /choice\.relatedToFocus/);
  assert.match(plan, /choice\.availableByDefault/);
  assert.match(plan, /toneCountFilters/);
  assert.match(plan, /plan\.selectedIdiomForm/);
  assert.match(plan, /onSetIdiomChordToneCount/);
  assert.match(plan, /plan\.voiceLeading/);
  assert.match(plan, /onChoose\(candidate\)/,
    "voice-leading choices must dispatch the shared candidate and ordered path selection");
  assert.match(plan, /candidate\.availableWhenThreeToneSameDirectionFiltered/,
    "the seventh filter must consume the shared path classification");
  assert.match(plan, /plan\.voiceLeading\?\.pathways/);
  assert.match(plan, /onChoose\(pathway, placement\)/,
    "pathway insertion must dispatch the shared pathway id and the chosen placement");
  assert.match(plan, /view\.placementOptions/,
    "the placement toggle must come from the shared projection, not a local list");
  assert.match(plan, /pathway\.metricsLabel/,
    "tension metrics must be rendered from the shared label, not recomputed in React");
  assert.equal(plan.includes("node.tension >") || plan.includes("resolutionDrop >"), false,
    "React must not re-derive tension thresholds from the raw metrics");
  assert.equal(plan.includes("pitchClasses.map"), false,
    "the right panel must not reconstruct voice-leading candidates from pitch classes");
  assert.match(plan, /const strings = plan\?\.strings/);
  assert.equal(plan.includes("function keyLabel"), false,
    "dynamic key labels must come from PracticePlanView");
  assert.equal(plan.includes("idiomDefinitionId"), false,
    "desktop uses flat idiom choices, so Web must not restore a definition dropdown");
  assert.equal(plan.includes("idiomVariantId"), false,
    "shared projection exposes base idiom choices, so Web must not restore a concrete-variant dropdown");
  assert.equal(plan.includes('<select value={catalogChoiceId}'), false,
    "desktop uses the expanded grouped chord catalog, so Web must not collapse it to a native select");
});

test("wide workbench side panel exposes an accessible pointer and keyboard resizer", async () => {
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const resizer = await readFile(join(sourceRoot, "ResizableWorkbenchSide.jsx"), "utf8");
  assert.match(app, /<ResizableWorkbenchSide>/);
  assert.match(resizer, /role="separator"/);
  assert.match(resizer, /setPointerCapture/);
  assert.match(resizer, /onPointerMove/);
  assert.match(resizer, /ArrowLeft/);
  assert.match(resizer, /aria-valuenow/);
});

test("the free-practice shell exposes script and Worker loading states", async () => {
  const html = await readFile(join(sourceRoot, "../index.html"), "utf8");
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const overlay = await readFile(join(sourceRoot, "AudioSettingsDialog.tsx"), "utf8");
  const worker = await readFile(join(sourceRoot, "engine-worker.js"), "utf8");
  assert.match(html, /boot-loading/,
    "the initial HTML must remain informative while the entry module downloads");
  assert.match(worker, /postMessage\(\{ type: "ready" \}\)/,
    "the shell needs an explicit signal after the Worker module has evaluated");
  assert.match(app, /data\.type === "ready"/);
  assert.match(overlay, /app-loading-overlay/);
  assert.match(app, /documentRequestIdRef\.current !== startupDocumentRequestBase[\s\S]*createDocument\(\)/,
    "a first visit without recovery data must open a new shared practice document");
});

test("edit playback is consumed from the shared free-practice update", async () => {
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const playback = await readFile(join(sourceRoot, "PracticePlaybackController.ts"), "utf8");
  const worker = await readFile(join(sourceRoot, "engine-worker.js"), "utf8");
  assert.match(app, /playback\.requestEdit\(data\.update\.editPlayback\)/);
  assert.match(playback, /trackCursor: false/,
    "shared edit playback must remain audible without driving the transport playhead");
  assert.match(playback, /message\.trackCursor === false \? null : message\.range/,
    "an edit excerpt must schedule audio without publishing a visible cursor range");
  assert.match(worker, /trackCursor: message\.trackCursor/,
    "the playback worker must preserve the edit playback presentation flag");
  assert.equal(app.includes("data.update.effect.kind === \"WRITING_APPLIED\""), false,
    "the Web shell must not infer edit playback from effect names");
});

test("finding severity colors drive the whole feedback card", async () => {
  const styles = await readFile(join(sourceRoot, "styles.css"), "utf8");
  const accents = ["INFO", "WARNING", "ERROR"].map((severity) => {
    const match = styles.match(new RegExp(
      `\\.practice-feedback-panel li\\[data-severity="${severity}"\\] \\{[^}]*--finding-accent: ([^;]+);`,
    ));
    assert.ok(match, `${severity} must define a feedback accent color`);
    return match[1];
  });
  assert.equal(new Set(accents).size, 3, "INFO, WARNING and ERROR must use distinct colors");
  assert.match(styles, /li::before \{[^}]*background: var\(--finding-accent\)/,
    "the status dot must use the severity color");
  assert.match(styles, /li strong \{[^}]*color: var\(--finding-accent\)/,
    "the finding title must use the severity color");
  assert.match(styles, /background: var\(--finding-background\)/,
    "the card background must use the severity color family");
});

test("partitioned workbench keeps the classic branch and exposes an accessible lower resizer", async () => {
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const plan = await readFile(join(sourceRoot, "PracticePlanPanel.jsx"), "utf8");
  const resizer = await readFile(join(sourceRoot, "ResizableLowerWorkbench.jsx"), "utf8");
  assert.match(app, /DEFAULT_WEB_PRACTICE_LAYOUT = "writing-with-lower-panels"/);
  assert.match(app, /"classic-layout"/);
  assert.match(app, /renderPlanPanel\(\["harmony"\]\)/);
  assert.match(app, /renderPlanPanel\(\["idioms"\]\)/);
  assert.match(plan, /open=\{chordDetailsInitiallyOpen \|\| undefined\}/);
  assert.match(plan, /plan\.chordDetail/);
  assert.match(plan, /<FrozenScore bundle=\{construction\.bundle\}/);
  assert.equal(plan.includes("diatonicSteps"), false,
    "chord-detail engraving must stay in the Kotlin renderer");
  assert.equal(plan.includes("onConfirmRoute"), false,
    "Web chord details are display-only");
  assert.match(resizer, /role="separator"/);
  assert.match(resizer, /aria-orientation="horizontal"/);
  assert.match(resizer, /setPointerCapture/);
  assert.match(resizer, /ArrowUp/);
});

test("timeline platforms only replay the common raw scene and forward input", async () => {
  const webTimeline = await readFile(join(sourceRoot, "HarmonyTimeline.jsx"), "utf8");
  for (const forbidden of [
    "fractionValue", "interpolate", "snappedFraction", "optimisticTimeline",
    "PracticeTimelineEdit", "includeFollowing:",
  ]) {
    assert.equal(webTimeline.includes(forbidden), false, `Web timeline must not contain ${forbidden}`);
  }
  assert.match(webTimeline, /scene\.drawObjects/);
  assert.match(webTimeline, /scene\.accessibility/);
  assert.match(webTimeline, /scene\.hoverTargets/,
    "Web hover feedback must replay the shared hover targets");
  assert.equal(webTimeline.includes("SHARED_BOUNDARY"), false,
    "Web must not re-derive hover priority from hit kinds");
  assert.equal(/cursor\s*:\s*["'](ew-resize|grab)["']/.test(webTimeline), false,
    "Cursors belong to the shared scene, not the browser shell");
  assert.match(webTimeline, /send\("DOWN"/);
  assert.match(webTimeline, /send\("MOVE"/);
  assert.match(webTimeline, /send\("UP"/);
  assert.match(webTimeline, /send\("CANCEL"/);

  const desktopTimeline = await readFile(join(
    webRoot,
    "../apps/desktop/src/main/kotlin/com/mecon/desktop/ui/exploration/FreePracticeEditorPanel.kt",
  ), "utf8");
  for (const forbidden of [
    "private fun HarmonicTimeline(", "timelineDragModifier", "TimelineDragMode",
    "HarmonyWorkspaceCommand.toTimelineEdit",
  ]) {
    assert.equal(desktopTimeline.includes(forbidden), false, `Desktop timeline must not contain ${forbidden}`);
  }
  assert.match(desktopTimeline, /PracticeTimelineSceneProjector\.project/);
  assert.match(desktopTimeline, /FreePracticeTimelineController\.handle/);
  assert.match(desktopTimeline, /FreePracticeTimelineController\.hoverTarget/,
    "Desktop hover feedback must resolve through the shared controller");
  assert.match(desktopTimeline, /\.pointerInput\(Unit\)/,
    "Desktop pointer receiver must survive gesture-state recomposition");
  assert.equal(desktopTimeline.includes("pointerInput(scene.generation, gesture)"), false,
    "Desktop must not cancel an active pointer stream when gesture state changes");
});

test("application and editor JavaScript never engrave score elements", async () => {
  const roots = [sourceRoot, join(webRoot, "packages/web-renderer")];
  const forbidden = [
    { label: "construct a Draw* command", pattern: /type\s*:\s*["'][^"']*Draw(?:Glyph|Line|Path|Bezier)["']/ },
    { label: "embed a notation glyph name", pattern: /notehead(?:Black|Half|Whole)/ },
    { label: "draw glyph text on Canvas", pattern: /\.fillText\s*\(/ },
    { label: "draw notation curves on Canvas", pattern: /\.bezierCurveTo\s*\(/ },
  ];
  for (const root of roots) {
    for (const path of await sourceFiles(root)) {
      if (path.includes(`${join("web-renderer", "test")}`)) continue;
      const source = await readFile(path, "utf8");
      for (const rule of forbidden) {
        assert.equal(rule.pattern.test(source), false, `${path} must not ${rule.label}`);
      }
    }
  }
});

test("service worker refreshes the application shell before using its offline copy", async () => {
  const source = await readFile(serviceWorkerPath, "utf8");
  const navigation = source.indexOf('event.request.mode === "navigate"');
  const network = source.indexOf("fetch(event.request)", navigation);
  const fallback = source.indexOf('caches.match("/index.html")', navigation);
  assert.ok(navigation >= 0, "navigation requests need an explicit update strategy");
  assert.ok(network > navigation && fallback > network, "navigation must be network-first with an offline fallback");
});

/**
 * A background worker that dies can never answer, and the session keeps the request active until
 * something tells it otherwise — the workbench then stays locked in RUNNING and only a page reload
 * recovers. Every search worker must therefore report crashes into the shared failure channel.
 */
test("every search worker routes crashes into the shared session failure channel", async () => {
  const worker = await readFile(join(sourceRoot, "engine-worker.js"), "utf8");
  const backgroundWorkers = await readFile(
    join(sourceRoot, "PracticeBackgroundWorkers.ts"), "utf8",
  );
  for (const failure of ["backgroundFailure", "teachingCatalogFailure", "findingFailure"]) {
    assert.ok(
      worker.includes(`case "${failure}"`),
      `the Worker must apply ${failure} through the session`,
    );
  }
  assert.ok(
    worker.includes("applyBackgroundFailure("),
    "writing crashes must reach FreePracticeSession.applyBackgroundFailure",
  );
  // Two death modes: a caught exception posted as `error`, and a worker that dies outright.
  assert.equal(
    (backgroundWorkers.match(/worker\.onerror\s*=/g) ?? []).length,
    2,
    "both the terminable solve worker and the resident search worker need an onerror",
  );
  const shell = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const toolbar = await readFile(join(sourceRoot, "PracticeTopToolbar.tsx"), "utf8");
  assert.ok(
    shell.includes("freePractice.writing.failed"),
    "the shell must surface the rollback instead of silently clearing the alert",
  );
  assert.match(worker, /message\.intent\?\.type === "cancelWriting"\) backgroundWorkers\.cancelWriting\(\)/,
    "an explicit cancel must terminate the non-cooperative solve worker");
  assert.match(backgroundWorkers, /writingWorkers\.delete\(request\.kind\)/,
    "a crashed writing worker must not remain registered as live");
  assert.match(toolbar, /data-control-id=\{id\}/);
  assert.match(toolbar, /type: "cancelWriting"/);
});

test("note-property bulk locks dispatch one shared atomic intent", async () => {
  const panel = await readFile(join(sourceRoot, "PracticeNoteProperties.tsx"), "utf8");
  assert.match(panel, /type: "setVoiceLocks"/);
  assert.match(panel, /type: "setStaffLocks"/);
  assert.doesNotMatch(panel, /selectedVoiceIds\.forEach|selectedStaffIds\.forEach/);
});

test("top-toolbar descriptors fail visibly when a platform control is missing", async () => {
  const toolbar = await readFile(join(sourceRoot, "PracticeTopToolbar.tsx"), "utf8");
  assert.match(toolbar, /Unsupported free-practice toolbar controls/);
  assert.doesNotMatch(toolbar, /descriptor\.groups\.filter\(/,
    "platforms must not silently omit descriptor groups");
});

test("browser playback lifecycle stays outside the React composition root", async () => {
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  const playback = await readFile(join(sourceRoot, "PracticePlaybackController.ts"), "utf8");
  assert.doesNotMatch(app, /new AudioContext|createOscillator|requestAnimationFrame\(advancePlayhead/);
  assert.match(playback, /class PracticePlaybackController/);
  assert.match(playback, /playbackRequestId !== this\.requestId/,
    "stale playback excerpts must be rejected by the playback owner");
  assert.match(playback, /this\.scheduleId\+\+/,
    "stopping playback must invalidate an in-flight asynchronous schedule");
  assert.match(playback, /dispose\(\)/,
    "the extracted owner must expose one lifecycle cleanup boundary");
});

test("an obsolete new/open frame cannot replace the latest archive", async () => {
  const app = await readFile(join(sourceRoot, "App.tsx"), "utf8");
  assert.match(app, /data\.documentRequestId !== documentRequestIdRef\.current/);
  assert.match(app, /pendingDocumentRecoveryRef\.current\.delete\(data\.documentRequestId\)/);
  assert.match(app, /const documentRequestId = \+\+documentRequestIdRef\.current;[\s\S]*loadMeconDocument\(source\)/,
    "opening a file must reserve its request before asynchronous archive parsing");
  assert.match(app, /documentRequestIdRef\.current !== startupDocumentRequestBase/,
    "late startup recovery must not overtake an explicit document action");
});
