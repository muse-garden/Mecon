import { existsSync, readFileSync, statSync } from "node:fs";
import { extname, join, resolve } from "node:path";

export const RHODY_WASM_OUTPUT_PATH = "rhody/rhody_wasm.wasm";
export const RHODY_WASM_PROJECT_PATH = join(
  "target", "wasm32-unknown-unknown", "release", "rhody_wasm.wasm",
);

export function resolveRhodyWasmSource(projectPath) {
  const configured = String(projectPath ?? "").trim();
  if (!configured) return null;
  const absolute = resolve(configured);
  return extname(absolute).toLowerCase() === ".wasm"
    ? absolute
    : join(absolute, RHODY_WASM_PROJECT_PATH);
}

export function rhodyWasmPlugin({
  projectPath = process.env.MECON_RHODY_PROJECT_PATH,
} = {}) {
  const sourcePath = resolveRhodyWasmSource(projectPath);
  const available = Boolean(sourcePath && existsSync(sourcePath) && statSync(sourcePath).isFile());
  let base = "/";
  const assetRoute = () => `${base.endsWith("/") ? base : `${base}/`}${RHODY_WASM_OUTPUT_PATH}`;

  return {
    name: "mecon-rhody-wasm",
    configResolved(config) {
      base = config.base || "/";
      if (sourcePath && !available) {
        config.logger.warn(`[rhody] WASM not found: ${sourcePath}; using the default synthesizer`);
      }
    },
    configureServer(server) {
      if (!available) return;
      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url ?? "/", "http://localhost").pathname;
        const routes = new Set([`/${RHODY_WASM_OUTPUT_PATH}`, assetRoute()]);
        if (!routes.has(pathname)) {
          next();
          return;
        }
        response.statusCode = 200;
        response.setHeader("Content-Type", "application/wasm");
        response.setHeader("Content-Length", statSync(sourcePath).size);
        response.end(readFileSync(sourcePath));
      });
    },
    generateBundle() {
      if (!available) return;
      this.emitFile({
        type: "asset",
        fileName: RHODY_WASM_OUTPUT_PATH,
        source: readFileSync(sourcePath),
      });
    },
  };
}
