# Mecon 介绍网站

网站源码位于 `web/site/`，当前自由练习 Web 应用仍保留在
`web/apps/free-practice/`，发布构建会把它输出到 `/demo/free-practice/`。

## 本地构建

在仓库根目录执行：

```bash
cd web
node scripts/build-pages.mjs
```

脚本会先用 Cargo 构建 Rhody 的 WebAssembly 音色库，再构建主页和自由练习；产物位于
`web/dist/`。默认从相邻的 `vst-experiment/rhody` 工程读取 Rhody，也可以通过
`--rhody-project <路径>` 指定工程或已经构建好的 `.wasm` 文件。仅在明确接受默认合成器回退时使用
`--skip-rhody`。

构建后直接启动本地预览：

```bash
node scripts/build-pages.mjs --preview
```

## 发布到 GitHub Pages

目标仓库需要提前创建，并且当前 Git 凭据需要具备 push 权限：

```bash
cd web
node scripts/build-pages.mjs --repo https://github.com/<owner>/<pages-repo>.git
```

省略 `--base-path` 时，脚本会从目标仓库名推断 GitHub Pages 项目路径；`<owner>.github.io`
用户页保持根路径。需要部署到自定义域名、Cloudflare Pages 或 Vercel 时，使用 `--base-path /`。

如果目标仓库是项目页而不是用户页，可传入仓库路径，让资源使用正确的前缀：

```bash
node scripts/build-pages.mjs \
  --base-path /<pages-repo>/ \
  --repo https://github.com/<owner>/<pages-repo>.git
```

脚本会构建主页和自由练习、清理目标仓库工作区、提交并 push 当前构建。GitHub Pages 的发布源应设置为目标仓库的 `main` 分支根目录。
