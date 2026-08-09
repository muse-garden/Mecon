import { fileURLToPath, URL } from "node:url";
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { rhodyWasmPlugin } from "./rhody-vite.js";
import { projectLogoPlugin, websiteResourcesPlugin } from "./site-assets-vite.js";

export default defineConfig(({ mode }) => {
  const appRoot = fileURLToPath(new URL(".", import.meta.url));
  const env = loadEnv(mode, appRoot, "MECON_");
  return {
    plugins: [react(), projectLogoPlugin(), websiteResourcesPlugin(), rhodyWasmPlugin({
      projectPath: process.env.MECON_RHODY_PROJECT_PATH ?? env.MECON_RHODY_PROJECT_PATH,
    })],
    base: process.env.MECON_PAGES_BASE ?? "/",
    resolve: {
      alias: {
        "@mecon/frozen-score/react": fileURLToPath(new URL("../../packages/frozen-score/react.js", import.meta.url)),
        "@mecon/web-renderer/editor/react": fileURLToPath(new URL("../../packages/web-renderer/editor/react.jsx", import.meta.url)),
        "@mecon/web-renderer/editor": fileURLToPath(new URL("../../packages/web-renderer/editor/index.js", import.meta.url)),
        "@mecon/web-renderer/react": fileURLToPath(new URL("../../packages/web-renderer/react.js", import.meta.url)),
        "@mecon/frozen-score": fileURLToPath(new URL("../../packages/frozen-score/index.js", import.meta.url)),
        "@mecon/web-renderer": fileURLToPath(new URL("../../packages/web-renderer/index.js", import.meta.url)),
      },
    },
    publicDir: false,
    build: {
      target: "es2022",
      outDir: process.env.MECON_PAGES_OUT_DIR ?? "dist",
      emptyOutDir: true,
    },
    worker: {
      format: "es",
    },
  };
});
