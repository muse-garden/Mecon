/**
 * Builds only the browser-owned `.mecon` container. The score and practice payload are produced by
 * the shared Kotlin preset/session; this adapter merely gives them stable archive paths.
 */
export function createNewPracticeDocument({ score, document, module }, now = Date.now()) {
  if (!score?.id) throw new Error("新建自由练习缺少乐谱 ID");
  if (!module?.id || !module?.type || !Number.isInteger(module.schemaVersion)) {
    throw new Error("新建自由练习缺少模块描述");
  }
  const scorePath = `scores/${score.id}.json`;
  const geometryPath = `geometry/${score.id}.json`;
  const modulePath = `modules/${module.id}.json`;
  const title = score.metadata?.title || "自由练习";
  const manifest = {
    formatVersion: 1,
    engineVersion: "free-practice-web",
    createdAt: now,
    modifiedAt: now,
    activeScoreId: score.id,
    scores: [{ id: score.id, title, path: scorePath, geometryPath }],
    modules: [{ ...module, scoreId: score.id, path: modulePath }],
    workspace: { activeModuleId: module.id, selectedScoreIds: [score.id] },
  };
  const moduleEntry = { ...module, scoreId: score.id, payload: document };
  return {
    entries: new Map([
      ["manifest.json", JSON.stringify(manifest)],
      [scorePath, JSON.stringify(score)],
      [modulePath, JSON.stringify(moduleEntry)],
    ]),
    manifest,
    scores: new Map([[score.id, score]]),
    modules: new Map([[module.id, moduleEntry]]),
    opaqueModules: new Map(),
  };
}
