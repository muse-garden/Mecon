const aliases = new Map([
  ["@mecon/frozen-score", new URL("../packages/frozen-score/index.js", import.meta.url).href],
  ["@mecon/web-renderer", new URL("../packages/web-renderer/index.js", import.meta.url).href],
  ["@mecon/web-renderer/editor", new URL("../packages/web-renderer/editor/index.js", import.meta.url).href],
  ["@mecon/web-renderer/editor/react", new URL("../packages/web-renderer/editor/react.jsx", import.meta.url).href],
]);

/** Makes pure Node tests independent of Windows workspace-symlink support. */
export async function resolve(specifier, context, nextResolve) {
  const url = aliases.get(specifier);
  if (url) return { url, shortCircuit: true };
  return nextResolve(specifier, context);
}
