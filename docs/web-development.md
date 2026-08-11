# Web 端构建、运行与测试

本文说明仓库内 Web 工作区的本地开发流程。自由练习的领域接入见
[自由练习功能扩展指南](exploration/free-practice-extension-guide.md)，通用乐谱编辑协议见
[乐谱编辑多端接入规范](score-editing-multiplatform.md)。

## 1. 组成与运行边界

```text
React main thread
  ├─ 文件、恢复、DOM、Canvas/SVG、Web MIDI/Audio
  └─ plain JSON message
          ↓
engine-worker.js（串行提交、排版）
  ├─ MeconFreePractice / MeconScoreEditor
  └─ search-worker.js（写作、目录、finding）
          ↓
Kotlin/JS commonMain：session → core → computed → renderer
```

| 目录 | 职责 |
|---|---|
| `web/apps/free-practice/` | React 工作台、Worker、文件/恢复、浏览器输入与 E2E |
| `web/packages/frozen-score/` | 冻结几何重放、命中、`.mecon` 浏览器容器读写 |
| `web/packages/web-renderer/` | Kotlin/JS facade 包装及公共 `ScoreEditor` React 组件 |
| `bridge/web-engine/` | Kotlin/JS 字符串边界与 npm payload 构建 |
| `features/free-practice/` | 自由练习共享 session、协议、投影及后台执行器 |

React 不直接修改 `StorageScore`/workspace，也不运行完整 compute、solve 或 MIDI 转换。

播放时，Worker 仍负责从共享 session 生成 excerpt，主线程只负责 AudioContext 调度。共享
`ScoreEditorSurface` 通过轻量外部 cursor store 消费 render frame 的 `playbackAnchors` 与
`timePositions` 按逐音符锚点离散绘制播放线；和声 `timeAxis` 只负责时间轴对齐，不能代替播放锚点。
每个播放锚点携带 expanded MIDI tick，excerpt 同时返回 `startTick`、`endTick` 和
`secondsPerTick`；AudioContext 音符调度与播放线必须共享这套 tick 时间尺度，禁止按选段总时值比例
反算播放线。播放帧不会触发 Kotlin 重排版或整张乐谱 Canvas 重绘。
暂停态保留当时的 cursor tick，使播放线停在当前逐音符锚点；内部恢复偏移则独立吸附到当前音符
起点，恢复时完整重播该音。从选中播放优先 seek 到统一谱面事件选区，没有事件选区时才回退和声槽，
然后继续至谱尾。

## 2. 环境准备

- JDK 17 或更高版本；
- Node.js 20 LTS 或更新的受支持版本，npm 使用仓库的 `package-lock.json`；
- 浏览器：默认使用 Playwright 自带 Chromium，无需系统浏览器；如需在具体渠道复验，设 `MECON_E2E_CHANNEL=msedge`（或 `chrome`）；
- 同级目录 `../mecon-components`。根 `settings.gradle.kts` 无条件包含该 composite build。

目录应为：

```text
projects/
├── mecon-components/
└── Mecon/
```

首次安装：

```powershell
cd web
npm ci
npx playwright install chromium
```

`npm ci` 只安装 JavaScript 依赖，不生成 Kotlin/JS 引擎。

## 3. 首次启动

在仓库根目录生成 Kotlin/JS npm payload：

```powershell
cd web
npm run prepare:engine
```

该命令由 `web/scripts/prepare-engine.mjs` 按平台选择 `gradlew` / `gradlew.bat`，调用
`:bridge:web-engine:prepareNpmPackage`，先编译 production library，再把 ES modules 同步到
`web/packages/web-renderer/kotlin/`。此目录是构建产物，不提交 Git。

脚本默认跳过根 Kotlin/JS 的 Yarn install/lock 任务：Web workspace 依赖由 `npm ci` 管理，而 Windows 上
重复链接 Gradle 聚合测试包会触发 `EISDIR`。**代价是 Kotlin/JS 依赖漂移与 yarn lock 不再被校验**，因此
升级 Kotlin 或任一 JS 依赖后（以及定期）必须完整跑一次：

```powershell
npm run prepare:engine:full
```

启动开发服务器：

```powershell
npm run dev:free-practice
```

默认地址为 `http://localhost:5173/`。开发模式不注册 Service Worker；Bravura 字体、SMuFL metadata
和 `sw.js` 来自 `apps/desktop/src/main/resources/`，由 Vite `publicDir` 暴露。

自由练习默认选择 Rhody 钢琴音色，但 Mecon 不声明 Rhody 包依赖。需要启用时，把本机 Rhody 项目
路径作为构建环境变量传入：

```powershell
$env:MECON_RHODY_PROJECT_PATH="F:\projects\vst-experiment\rhody"
npm run dev:free-practice
```

也可在 `web/apps/free-practice/.env.local` 中设置同名变量，供本机 dev 与 build 共用。

Vite 从该项目的 `target/wasm32-unknown-unknown/release/rhody_wasm.wasm` 读取产物；变量也可直接指向
`.wasm` 文件。dev server 会通过 Mecon 同源地址 `/rhody/rhody_wasm.wasm` 提供它，production/Pages
构建则将文件复制为输出目录内的 `rhody/rhody_wasm.wasm`，因此部署不需要 Rhody 服务器或跨域配置。
Rhody 路径为空、项目或 WASM 不存在、浏览器不支持 AudioWorklet，或初始化失败时，播放自动回退到
原有默认合成器。路径只在构建进程中使用，不会写入浏览器产物。

工作台首次启动且没有自动恢复数据时会直接新建自由练习，也可选择桌面导出的 `.mecon`。新建动作在 Worker 中调用共享
`FreePracticePreset` 创建 document/score/session，React 只组装浏览器负责的 `.mecon` 容器，不复制
workspace 或乐谱初始化规则。顶部工具栏将“新建”置于最左侧；打开、新建仅在当前文档自上次打开、
新建或实际保存成功后发生持久化修改时确认。自动恢复同时保存文档与未保存标记，刷新不会把恢复内容误判为
已保存；打开和新建会写入新的干净恢复基线。保存优先使用 File System Access API，只有文件写入并
关闭成功才标记为已保存；用户取消或写入失败仍保持未保存。不支持该 API 的浏览器回退为下载，因无法
获知下载对话框结果，不会把下载动作本身当作保存成功。也可生成仓库自有测试文件：

静态 HTML 会覆盖入口 JS 下载阶段；React 挂载后由 Worker `ready` 握手覆盖引擎分包加载，新建、打开与
恢复则保持加载遮罩直至对应首个完整 frame。未打开文档时，时间轴显示空状态而非生成中状态。

```powershell
node --import ./scripts/workspace-register.mjs apps/free-practice/scripts/create-e2e-fixture.mjs
```

文件生成在 `web/build/e2e/`，其中 `free-practice-f1.mecon` 是常用自由练习样例。生成 fixture 前必须
已经执行 `prepare:engine`。

## 4. 日常开发循环

| 改动 | 需要执行 |
|---|---|
| 仅 React/CSS/纯 JS adapter | Vite 热更新；提交前跑 `npm test` 和相关 E2E |
| `bridge/web-engine` 或任一 Kotlin/JS 依赖模块 | 重新执行 `npm run prepare:engine`，再重启 Vite |
| frozen wire/renderer 命令 | 重建引擎，并同时验证 Canvas、SVG 与 fixture |
| `.mecon`/自由练习持久化模型 | 先更新 `docs/data_model/`，再重建 fixture 和桌面回读测试 |

不要手工修改 `web/packages/web-renderer/kotlin/`；源改动必须发生在 Kotlin 模块并通过 Gradle 同步。

## 5. 生产构建与预览

```powershell
cd web
npm run prepare:engine
npm run build:free-practice
npm run preview --workspace @mecon/free-practice-web -- --host 127.0.0.1
```

单独构建自由练习时，输出位于 `web/apps/free-practice/dist/`，资源默认以站点根路径加载。
如果要构建介绍主页和 GitHub Pages 站点，使用统一构建入口：

```powershell
cd web
npm run build:pages
```

统一构建会先编译 Rhody WASM。默认查找相邻的 `vst-experiment/rhody`，也可传入
`--rhody-project <路径>`；随后产物写入 `web/dist/`：介绍主页在 `/`，自由练习在
`/demo/free-practice/`，Logo、Rhody、字体、Bravura metadata 与 Service Worker 会一起放入对应目录。
传入 `--preview` 可在构建后启动本地静态服务。也可以把 `--repo <git-url>` 传给
`node scripts/build-pages.mjs`，在构建完成后自动提交并 push 到目标 Pages 仓库；完整说明见
`docs/web-site.md`。

自由练习的资源 URL 会跟随 Vite base path，统一 Pages 构建会同步生成对应 scope 与 cache key 的
Service Worker。项目页部署时，给构建脚本传入 `--base-path /<pages-repo>/`。

## 6. 测试层级

首次或 Kotlin 改动后的推荐顺序：

```powershell
cd web
npm run test:engine
npm run build:free-practice
npm run test:e2e
```

| 命令 | 覆盖范围 |
|---|---|
| `npm test` | 纯 Node、公共 editor、Kotlin/JS trace/smoke；要求 payload 已存在且最新 |
| `npm run test:engine` | 重新生成 Kotlin/JS payload 后运行全部 Node 测试 |
| `npm run build:free-practice` | production Vite 构建 |
| `npm run test:e2e` | 生成 fixture、E2E 构建、启动 preview、运行常规 Playwright（默认 Chromium） |
| `.\gradlew.bat :apps:desktop:freePracticeBrowserExportTest` | 桌面回读 E2E 导出的 `.mecon`，须在 `test:e2e` 之后运行 |
| `npm run test:e2e:soak` | 默认 30 分钟资源稳定性测试 |

缩短 soak 仅用于诊断：

```powershell
$env:MECON_SOAK_MINUTES="2"
npm run test:e2e:soak
Remove-Item Env:MECON_SOAK_MINUTES
```

正式验收不得用缩短结果代替默认 30 分钟。E2E runner 自己管理 4173 preview server；不要在该端口
预先启动另一个实例。

Kotlin 侧最小回归见扩展指南；跨端 trace 必须在 JVM 与生成后的 Kotlin/JS 两边都通过。

## 7. 常见问题

### 找不到 Kotlin 模块或导出类

通常是 `web/packages/web-renderer/kotlin/` 缺失或过期。运行 `npm run prepare:engine`，不要复制旧构建
目录或在 JS 中加兼容实现。

### Kotlin/JS 构建出现 `EISDIR ... symlink`

应使用 `npm run prepare:engine`，不要把它替换成未排除 `kotlinNpmInstall` 的裸 Gradle 任务。若曾手工
运行裸任务，停止其他 Gradle/Node 进程后直接重跑 npm script；不要删除源码目录规避链接冲突。

### Gradle 找不到 `mecon-components`

确认同级目录名准确为 `mecon-components`。即使只构建 Web bridge，Gradle settings 也会解析该
composite build。

### Playwright 找不到浏览器

运行 `npx playwright install chromium`。默认不再固定 Edge channel；只有设置 `MECON_E2E_CHANNEL` 时
才需要对应的系统浏览器。

### Bravura/metadata 返回 404

从 `web/` 工作区脚本启动应用，确认 `vite.config.js` 的 `publicDir` 仍指向桌面 resources；不要把字体
复制到 React 源目录形成第二份资源。

### 生产预览仍显示旧页面

开发模式不会注册 Service Worker；production preview 会。先停止旧 preview，清除该 origin 的
Service Worker/cache 后重试。导航策略应保持 network-first，离线壳只作回退。

### 编辑后谱面未刷新或无谓全量排版

先检查 session 返回的权威 `scoreChanged` 与 render hint。排版输入是 `(score, timeline)`：Worker 在
`scoreChanged != false` **或时间轴投影变化**时重排，两者都不满足时复用上一帧。不得在 React 根据
effect 名称猜测是否变化——是否落盘同样以 session 的 `documentChanged` 为准（提交后仍报 `INVALID`
的写作结果就无法用 effect 名单表达）。

自由练习 surface 尺寸变化会让 Worker 以新 viewport 重投影，但不会把剩余宽度分摊给 time-axis
segment；默认标尺固定为 144 px/四分音符。引擎输出 `contentOriginX`、intrinsic/surface/viewport width、
scroll extent 与 surface-local raw timeline scene，短谱 surface 至少铺满主区。React 只重放 draw/a11y
objects 和转发原始输入，不得插值 time↔x、用 CSS 百分比重建几何、把 `StaffSpace` 当 CSS px 或拉伸
Canvas。门禁见[自由练习 Web/桌面统一计划](exploration/free-practice-web-desktop-parity-plan.md)。
音符与休止符拖动 preview 由 Web engine 调用 renderer 的 `TransposePreviewComputer` /
`RestMovePreviewComputer` 返回完整命令；Web 只隐藏原事件并重放 base/moved command layer，不能
自行拼乐谱元素、推导符杆，或在引擎不可用时退回到 JS 元素位移 ghost。`frozen-score` 中的
Canvas/SVG `RenderCommand` 通用解释器是平台后端，不得在其中加入按音符、休止符等元素类型分支的刻谱规则。

复合 session 在 `scoreSession.dispatch` 之外提交谱面时，必须在每次操作入口调用
`ScoreEditingSession.beginExternalOperation()`，否则首次提交后每一帧都会声称谱面已变。

## 8. 提交前清单

- [ ] Kotlin 变化后重新生成 payload，未提交 `web/packages/web-renderer/kotlin/`；
- [ ] `npm run test:engine` 与 production build 通过；
- [ ] pointer/keyboard/文件变化有真实 Playwright 路径；
- [ ] `.mecon` 变化通过浏览器导出→桌面回读：先跑 `npm run test:e2e`（F1 用例写出
      `web/build/e2e/browser-free-practice-export.mecon`），再跑
      `.\gradlew.bat :apps:desktop:freePracticeBrowserExportTest`（可用
      `-Pfreepractice.browser.export.path=...` 指定其他归档）。该任务不在 `:apps:desktop:test` 内，
      属性缺失时会失败而不是空跑；
- [ ] React 未新增音乐规则、持久化 reducer、全谱主线程计算或第二套历史；
- [ ] 对应能力矩阵、扩展指南或数据模型文档已同步。
