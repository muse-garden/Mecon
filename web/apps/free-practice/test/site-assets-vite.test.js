import assert from "node:assert/strict";
import test from "node:test";

import {
  PROJECT_LOGO_OUTPUT_PATH,
  PROJECT_LOGO_SOURCE_PATH,
  WEBSITE_RESOURCE_PATHS,
  projectLogoPlugin,
  websiteResourcesPlugin,
} from "../site-assets-vite.js";

test("project logo is emitted at a base-relative website path", () => {
  const plugin = projectLogoPlugin();
  plugin.configResolved({ base: "/Mecon/demo/free-practice/", logger: { warn() {} } });

  let emitted;
  plugin.generateBundle.call({ emitFile(asset) { emitted = asset; } });
  assert.equal(emitted.fileName, PROJECT_LOGO_OUTPUT_PATH);
  assert.ok(emitted.source.length > 0);
  assert.match(PROJECT_LOGO_SOURCE_PATH, /logo\.png$/);
});

test("website resources include only files used by the web app", () => {
  const plugin = websiteResourcesPlugin();
  plugin.configResolved({ base: "/", logger: { warn() {} } });

  const emitted = [];
  plugin.generateBundle.call({ emitFile(asset) { emitted.push(asset); } });

  assert.deepEqual(
    emitted.map((asset) => asset.fileName).sort(),
    Object.keys(WEBSITE_RESOURCE_PATHS).sort(),
  );
  assert.ok(emitted.every((asset) => asset.source.length > 0));
  assert.ok(emitted.every((asset) => !asset.fileName.endsWith(".sf3")));
});
