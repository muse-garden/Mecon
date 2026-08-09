import { cp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, extname, join, relative, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const webRoot = resolve(scriptDir, "..");
const repoRoot = resolve(webRoot, "..");
const siteSource = join(webRoot, "site");
const outputRoot = join(webRoot, "dist");
const freePracticeOutput = join(outputRoot, "demo", "free-practice");
const defaultRhodyProject = resolve(repoRoot, "..", "vst-experiment", "rhody");
const rhodyWasmRelativePath = join("target", "wasm32-unknown-unknown", "release", "rhody_wasm.wasm");

function requiredValue(argv, index, argument) {
  const value = argv[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`${argument} requires a value`);
  return value;
}

function parseArgs(argv) {
  const options = {
    basePath: process.env.MECON_PAGES_SITE_BASE ?? null,
    repo: process.env.MECON_PAGES_REPO ?? null,
    message: process.env.MECON_PAGES_COMMIT_MESSAGE ?? "chore: publish Mecon website",
    preview: false,
    previewHost: process.env.MECON_PAGES_PREVIEW_HOST ?? "127.0.0.1",
    previewPort: Number(process.env.MECON_PAGES_PREVIEW_PORT ?? 4173),
    rhodyProject: process.env.MECON_RHODY_PROJECT_PATH ?? defaultRhodyProject,
    buildRhody: true,
  };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--base-path") options.basePath = requiredValue(argv, index++, argument);
    else if (argument === "--repo" || argument === "--publish") options.repo = requiredValue(argv, index++, argument);
    else if (argument === "--message") options.message = requiredValue(argv, index++, argument);
    else if (argument === "--preview") options.preview = true;
    else if (argument === "--host") options.previewHost = requiredValue(argv, index++, argument);
    else if (argument === "--port") options.previewPort = Number(requiredValue(argv, index++, argument));
    else if (argument === "--rhody-project") options.rhodyProject = requiredValue(argv, index++, argument);
    else if (argument === "--skip-rhody") options.buildRhody = false;
    else if (argument === "--help" || argument === "-h") {
      console.log(`Usage: node scripts/build-pages.mjs [options]

Build options:
  --base-path <path>       Deployment base path (inferred from --repo when omitted)
  --rhody-project <path>  Rhody project or prebuilt .wasm path
  --skip-rhody            Build without Rhody and keep the synthesizer fallback

After building (choose at most one):
  --preview               Serve the built site locally until interrupted
  --host <host>           Preview host (default: 127.0.0.1)
  --port <port>           Preview port (default: 4173)
  --repo <git-url>        Commit and push the build to a GitHub Pages repository
  --publish <git-url>     Alias for --repo
  --message <text>        Publish commit message`);
      process.exit(0);
    } else throw new Error(`Unknown argument: ${argument}`);
  }
  if (options.preview && options.repo) throw new Error("Choose either --preview or --repo, not both");
  if (!Number.isInteger(options.previewPort) || options.previewPort < 1 || options.previewPort > 65535) {
    throw new Error("--port must be an integer between 1 and 65535");
  }
  return options;
}

function inferBasePath(repo) {
  if (!repo) return "/";
  const withoutSuffix = String(repo).trim().replace(/[\\/]$/, "").replace(/\.git$/i, "");
  const name = withoutSuffix.split(/[\\/:]/).filter(Boolean).at(-1);
  if (!name || name.toLowerCase().endsWith(".github.io")) return "/";
  return `/${name}/`;
}

function normalizeBasePath(value) {
  const raw = String(value || "/").trim();
  const withLeadingSlash = raw.startsWith("/") ? raw : `/${raw}`;
  return withLeadingSlash.endsWith("/") ? withLeadingSlash : `${withLeadingSlash}/`;
}

function run(command, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      stdio: "inherit",
      shell: command.endsWith(".cmd"),
      ...options,
    });
    child.on("error", reject);
    child.on("exit", (code) => code === 0
      ? resolvePromise()
      : reject(new Error(`${command} ${args.join(" ")} exited with code ${code}`)));
  });
}

function runCapture(command, args, options = {}) {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      shell: command.endsWith(".cmd"),
      stdio: ["ignore", "pipe", "pipe"],
      env: options.env,
    });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.on("error", reject);
    child.on("exit", (code) => resolvePromise({ code, stdout, stderr }));
  });
}

async function copyLandingPage() {
  await mkdir(outputRoot, { recursive: true });
  await cp(siteSource, outputRoot, { recursive: true });
  await writeFile(join(outputRoot, ".nojekyll"), "", "utf8");
}

async function isFile(path) {
  try {
    return (await stat(path)).isFile();
  } catch {
    return false;
  }
}

async function buildRhody(options) {
  if (!options.buildRhody) {
    console.warn("Skipping Rhody build; free practice will use its fallback synthesizer.");
    return null;
  }
  const configuredPath = resolve(options.rhodyProject);
  if (extname(configuredPath).toLowerCase() === ".wasm") {
    if (!await isFile(configuredPath)) throw new Error(`Rhody WASM not found: ${configuredPath}`);
    return configuredPath;
  }
  if (!await isFile(join(configuredPath, "Cargo.toml"))) {
    throw new Error(`Rhody project not found: ${configuredPath}. Pass --rhody-project <path> or --skip-rhody.`);
  }
  await run("cargo", ["build", "--release", "--target", "wasm32-unknown-unknown", "-p", "rhody-wasm"], {
    cwd: configuredPath,
  });
  const wasmPath = join(configuredPath, rhodyWasmRelativePath);
  if (!await isFile(wasmPath)) throw new Error(`Rhody build did not produce ${wasmPath}`);
  console.log(`Built Rhody WASM at ${wasmPath}`);
  return configuredPath;
}

function serviceWorkerSource(basePath, includeRhody) {
  return `const RESOURCE_FINGERPRINT = "mecon-free-practice-pages-v2";
const CACHE_NAME = \`mecon-\${RESOURCE_FINGERPRINT}\`;
const BASE_URL = new URL(${JSON.stringify(basePath)}, self.location.origin).href;
const SHELL = ["", "index.html", "logo.png", "fonts/Bravura.otf", "bravura/bravuraMetadata.json", "bravura/glyphnames.json"${includeRhody ? ', "rhody/rhody_wasm.wasm"' : ""}]
  .map((path) => new URL(path, BASE_URL).href);

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(caches.keys().then((keys) => Promise.all(
    keys.filter((key) => key.startsWith("mecon-") && key !== CACHE_NAME).map((key) => caches.delete(key)),
  )));
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  if (event.request.method !== "GET") return;
  if (event.request.mode === "navigate") {
    event.respondWith(fetch(event.request).then((response) => {
      if (response.ok) {
        const copy = response.clone();
        event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.put(new URL("index.html", BASE_URL).href, copy)));
      }
      return response;
    }).catch(() => caches.match(new URL("index.html", BASE_URL).href)));
    return;
  }
  event.respondWith(caches.match(event.request).then((cached) => cached ?? fetch(event.request).then((response) => {
    if (response.ok && new URL(event.request.url).origin === self.location.origin) {
      const copy = response.clone();
      event.waitUntil(caches.open(CACHE_NAME).then((cache) => cache.put(event.request, copy)));
    }
    return response;
  })));
});
`;
}

async function build(options) {
  const siteBase = normalizeBasePath(options.basePath ?? inferBasePath(options.repo));
  const demoBase = `${siteBase}demo/free-practice/`.replace(/\\/g, "/");
  const rhodyProject = await buildRhody(options);
  await rm(outputRoot, { recursive: true, force: true });
  await copyLandingPage();
  const npm = process.platform === "win32" ? "npm.cmd" : "npm";
  await run(npm, ["run", "build", "--workspace", "@mecon/free-practice-web"], {
    cwd: webRoot,
    env: {
      ...process.env,
      MECON_PAGES_BASE: demoBase,
      MECON_PAGES_OUT_DIR: freePracticeOutput,
      MECON_RHODY_PROJECT_PATH: rhodyProject ?? "",
    },
  });
  const rhodyOutput = join(freePracticeOutput, "rhody", "rhody_wasm.wasm");
  if (rhodyProject && !await isFile(rhodyOutput)) {
    throw new Error(`Free-practice build is missing bundled Rhody WASM: ${rhodyOutput}`);
  }
  await writeFile(join(freePracticeOutput, "sw.js"), serviceWorkerSource(demoBase, Boolean(rhodyProject)), "utf8");
  console.log(`Built Mecon Pages site at ${outputRoot}`);
  console.log(`Landing page: ${siteBase}`);
  console.log(`Free practice: ${demoBase}`);
  return { siteBase, demoBase };
}

const contentTypes = new Map([
  [".css", "text/css; charset=utf-8"],
  [".html", "text/html; charset=utf-8"],
  [".js", "text/javascript; charset=utf-8"],
  [".json", "application/json; charset=utf-8"],
  [".otf", "font/otf"],
  [".png", "image/png"],
  [".wasm", "application/wasm"],
]);

async function preview(options, siteBase) {
  const prefix = siteBase === "/" ? "/" : siteBase.slice(0, -1);
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      let pathname = decodeURIComponent(url.pathname);
      if (prefix !== "/") {
        if (pathname !== prefix && !pathname.startsWith(`${prefix}/`)) {
          response.writeHead(404).end("Not found");
          return;
        }
        pathname = pathname.slice(prefix.length) || "/";
      }
      let filePath = resolve(outputRoot, `.${pathname}`);
      const insideOutput = filePath === outputRoot || !relative(outputRoot, filePath).split(sep).includes("..");
      if (!insideOutput) {
        response.writeHead(403).end("Forbidden");
        return;
      }
      if ((await stat(filePath).catch(() => null))?.isDirectory()) filePath = join(filePath, "index.html");
      const body = await readFile(filePath);
      response.writeHead(200, {
        "Content-Type": contentTypes.get(extname(filePath).toLowerCase()) ?? "application/octet-stream",
        "Cache-Control": "no-cache",
      });
      response.end(body);
    } catch {
      response.writeHead(404).end("Not found");
    }
  });
  await new Promise((resolvePromise, reject) => {
    server.once("error", reject);
    server.listen(options.previewPort, options.previewHost, resolvePromise);
  });
  console.log(`Previewing Mecon at http://${options.previewHost}:${options.previewPort}${siteBase}`);
  console.log("Press Ctrl+C to stop.");
}

async function clearDirectory(directory) {
  for (const entry of await readdir(directory)) {
    if (entry !== ".git") await rm(join(directory, entry), { recursive: true, force: true });
  }
}

async function publish(repo, message) {
  const tempRoot = join(webRoot, ".pages-publish");
  const checkout = join(tempRoot, "target");
  await rm(tempRoot, { recursive: true, force: true });
  await mkdir(tempRoot, { recursive: true });
  try {
    const remote = await runCapture("git", ["ls-remote", repo], { cwd: tempRoot });
    if (remote.code !== 0) {
      throw new Error(`Cannot access target repository: ${remote.stderr.trim() || repo}`);
    }
    if (remote.stdout.trim()) {
      await run("git", ["clone", "--depth", "1", repo, checkout], { cwd: tempRoot });
    } else {
      await mkdir(checkout, { recursive: true });
      await run("git", ["init", "-b", "main"], { cwd: checkout });
      await run("git", ["remote", "add", "origin", repo], { cwd: checkout });
      console.warn("Target repository is empty; initialized a new main branch.");
    }
    await clearDirectory(checkout);
    await cp(outputRoot, checkout, { recursive: true });
    await run("git", ["config", "user.name", process.env.GIT_AUTHOR_NAME ?? "Mecon Pages Bot"], { cwd: checkout });
    await run("git", ["config", "user.email", process.env.GIT_AUTHOR_EMAIL ?? "mecon-pages@users.noreply.github.com"], { cwd: checkout });
    await run("git", ["add", "--all"], { cwd: checkout });
    const status = await new Promise((resolvePromise, reject) => {
      const child = spawn("git", ["diff", "--cached", "--quiet"], { cwd: checkout });
      child.on("error", reject);
      child.on("exit", (code) => resolvePromise(code));
    });
    if (status === 0) {
      console.log("Target repository already contains this build; nothing to push.");
      return;
    }
    await run("git", ["commit", "-m", message], { cwd: checkout });
    await run("git", ["push", "origin", "HEAD"], { cwd: checkout });
    console.log(`Published ${outputRoot} to ${repo}`);
  } finally {
    await rm(tempRoot, { recursive: true, force: true });
  }
}

const options = parseArgs(process.argv.slice(2));
const result = await build(options);
if (options.repo) await publish(options.repo, options.message);
else if (options.preview) await preview(options, result.siteBase);
else console.log("Build complete. Pass --preview to serve locally or --repo <git-url> to publish.");
