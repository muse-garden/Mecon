import { spawn } from "node:child_process";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Builds the Kotlin/JS engine npm payload the cross-platform trace gates depend on.
 *
 * Picking the wrapper by platform keeps the required gates runnable on macOS and Linux; hard-coding
 * `gradlew.bat` made the Kotlin/JS trace replay a Windows-only check, which for a GPL project means
 * most contributors cannot run what CLAUDE.md declares mandatory.
 *
 * `kotlinStoreYarnLock` / `kotlinNpmInstall` are skipped because Kotlin's yarn workspace setup fails
 * with EISDIR on symlinked node_modules here. The cost is that Kotlin/JS dependency drift and the
 * yarn lock are no longer validated on every run; pass `--with-npm-install` (or set
 * MECON_ENGINE_FULL=1) periodically, and whenever Kotlin or a JS dependency is upgraded, to check
 * them.
 */
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const full = process.argv.includes("--with-npm-install") || process.env.MECON_ENGINE_FULL === "1";
const wrapper = resolve(root, process.platform === "win32" ? "gradlew.bat" : "gradlew");
const args = [
  ":bridge:web-engine:prepareNpmPackage",
  ...(full ? [] : ["-x", "kotlinStoreYarnLock", "-x", "kotlinNpmInstall"]),
  "--no-daemon",
  "--console=plain",
];

// Node refuses to spawn a .bat without a shell; the arguments here contain no shell metacharacters.
const child = spawn(wrapper, args, {
  cwd: root,
  stdio: "inherit",
  shell: process.platform === "win32",
});
child.on("error", (error) => {
  console.error(`Unable to run ${wrapper}: ${error.message}`);
  process.exit(1);
});
child.on("exit", (code) => process.exit(code ?? 1));
