import { existsSync, readFileSync, statSync } from "node:fs";
import { fileURLToPath } from "node:url";

export const WEBSITE_RESOURCE_PATHS = Object.freeze({
  "sw.js": fileURLToPath(new URL("../../../apps/desktop/src/main/resources/sw.js", import.meta.url)),
  "fonts/Bravura.otf": fileURLToPath(new URL("../../../apps/desktop/src/main/resources/fonts/Bravura.otf", import.meta.url)),
  "bravura/bravuraMetadata.json": fileURLToPath(new URL("../../../apps/desktop/src/main/resources/bravura/bravuraMetadata.json", import.meta.url)),
  "bravura/glyphnames.json": fileURLToPath(new URL("../../../apps/desktop/src/main/resources/bravura/glyphnames.json", import.meta.url)),
});
const WEBSITE_RESOURCE_CONTENT_TYPES = Object.freeze({
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".otf": "font/otf",
});

export const PROJECT_LOGO_OUTPUT_PATH = "logo.png";
export const PROJECT_LOGO_SOURCE_PATH = fileURLToPath(
  new URL("../../site/logo.png", import.meta.url),
);

export function projectLogoPlugin({ sourcePath = PROJECT_LOGO_SOURCE_PATH } = {}) {
  const available = existsSync(sourcePath) && statSync(sourcePath).isFile();
  let base = "/";
  const assetRoute = () => `${base.endsWith("/") ? base : `${base}/`}${PROJECT_LOGO_OUTPUT_PATH}`;

  return {
    name: "mecon-project-logo",
    configResolved(config) {
      base = config.base || "/";
      if (!available) config.logger.warn(`[site] Project logo not found: ${sourcePath}`);
    },
    configureServer(server) {
      if (!available) return;
      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url ?? "/", "http://localhost").pathname;
        if (!new Set([`/${PROJECT_LOGO_OUTPUT_PATH}`, assetRoute()]).has(pathname)) {
          next();
          return;
        }
        response.statusCode = 200;
        response.setHeader("Content-Type", "image/png");
        response.setHeader("Content-Length", statSync(sourcePath).size);
        response.end(readFileSync(sourcePath));
      });
    },
    generateBundle() {
      if (!available) return;
      this.emitFile({
        type: "asset",
        fileName: PROJECT_LOGO_OUTPUT_PATH,
        source: readFileSync(sourcePath),
      });
    },
  };
}

export function websiteResourcesPlugin({ resources = WEBSITE_RESOURCE_PATHS } = {}) {
  const entries = Object.entries(resources);
  let base = "/";

  return {
    name: "mecon-website-resources",
    configResolved(config) {
      base = config.base || "/";
      for (const [outputPath, sourcePath] of entries) {
        if (!existsSync(sourcePath) || !statSync(sourcePath).isFile()) {
          config.logger.warn(`[site] Website resource not found: ${outputPath} (${sourcePath})`);
        }
      }
    },
    configureServer(server) {
      server.middlewares.use((request, response, next) => {
        const pathname = new URL(request.url ?? "/", "http://localhost").pathname;
        const basePrefix = base.endsWith("/") ? base : `${base}/`;
        const outputPath = pathname.startsWith(basePrefix)
          ? pathname.slice(basePrefix.length)
          : pathname.slice(1);
        const sourcePath = resources[outputPath];
        if (!sourcePath || !existsSync(sourcePath)) {
          next();
          return;
        }
        const extension = outputPath.slice(outputPath.lastIndexOf("."));
        response.statusCode = 200;
        response.setHeader("Content-Type", WEBSITE_RESOURCE_CONTENT_TYPES[extension] ?? "application/octet-stream");
        response.setHeader("Content-Length", statSync(sourcePath).size);
        response.end(readFileSync(sourcePath));
      });
    },
    generateBundle() {
      for (const [outputPath, sourcePath] of entries) {
        if (!existsSync(sourcePath)) continue;
        this.emitFile({
          type: "asset",
          fileName: outputPath,
          source: readFileSync(sourcePath),
        });
      }
    },
  };
}
