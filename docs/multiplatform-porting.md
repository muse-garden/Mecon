# 多端移植设计

> 调研基线：2026-07-24；范围：排版、乐理/探索、播放与应用交互。
>
> 目标：排版与乐理规则保持一份实现；Web、Android、iOS、鸿蒙和桌面/平板只实现平台桥接、
> 绘制、音频与适合设备的交互。

## 1. 结论与边界

**结论：排版与乐理引擎具备高复用基础，但当前还不是可发布的多端库。**

- `api`、`core`、`theory`、`exploration` 和 `renderer` 的主要实现已在 `commonMain`，算法无需重写。
- 所有 KMP 模块目前只声明 `jvm` target；`commonMain` 只被 JVM 编译过，不能据目录名宣称已支持
  JS、Android 或 iOS。
- 排版算法与绘制后端已经分离：`renderer` 输出平台无关的 `RenderCommand` / `RenderElement`，
  Compose/Skia 绘制器位于桌面应用。这是复用排版和为 React 新写 Canvas 后端的关键基础。
- 乐理与探索协议已经分离：`SolverApi.describe / enumerate / solve` 及请求/结果 DTO 可序列化，
  React 可复用规则目录、进行枚举和求解，不重写规则。
- `ScoreToMidiConverter` 与 `MidiScore` 在 `commonMain`，可复用“乐谱转演奏事件”；真正发声的
  `JvmAudioEngine`、FluidSynth/JVM MIDI 不能复用，必须按平台实现 backend。
- 现有桌面 UI、快捷键和鼠标交互是 JVM/Compose Desktop 实现。手机应重做交互；平板只能复用
  抽出的命令、状态与部分 Compose 画布，不能直接搬运整个 `apps/desktop`。
- Android 与 iOS 可走正式 KMP target；HarmonyOS NEXT 不是 Kotlin 官方 target。公开案例已证明
  定制 Kotlin/Native OHOS target 可把共享源码编译为 `.so`，但工具链、依赖适配和运行时风险
  由接入方承担；鸿蒙必须设置独立可行性门禁，见 §7。

复用目标是**一份业务与几何算法源码**，不是强求一份 UI 或一份音频实现。

## 2. 当前实现审计

### 2.1 代码分布

下表为生产 Kotlin 源码粗略行数，不含测试和资源：

| 模块 | `commonMain` | JVM 平台实现 | 判断 |
|------|-------------:|-------------:|------|
| `api` | 8,403 | 2 | 数据四层、编辑状态高度可复用 |
| `core` | 11,083 | 0 | Compute、MusicXML、序列化主体可复用 |
| `renderer` | 25,668 | 102 | 排版/几何可复用，需补平台能力 |
| `theory` | 13,802 | 7 | 规则、搜索、求解可复用 |
| `exploration` | 3,127 | 0 | 请求、manifest、输出装配可复用 |
| `audio` | 1,217 | 891 | 转换模型可复用，播放 backend 平台化 |
| 和弦插件 core | 543 | 0 | 无 UI 内核可复用 |

这些数字说明迁移应先让现有源码接受多 target 编译，而不是另写 Web/Swift/ArkTS 版引擎。

### 2.2 已有正确接缝

```
StorageScore
  → RuntimeScore
  → ComputedScore
  → UnifiedLayoutResult
  → RenderElement(RenderCommand)
  → 平台绘制后端

CellRequest / SolverApi
  → theory ConstraintProgram / search
  → CellOutput(StorageScore + finding)

RuntimeScore
  → ScoreToMidiConverter
  → MidiScore
  → 平台音频 backend
```

- Storage/Runtime/Computed/Geometry 四层边界应原样保留。
- `RenderCommand` 已覆盖字形、线、矩形、路径、Bezier、文字、椭圆和 group。
- `RenderElement` 已含事件 ID、命中框、小节/系统/谱表索引，可支持 Web 点击、播放高亮和简单编辑。
- `RenderResult` 还包含 `SectionIndex`、空间索引和坐标转换器等进程内对象，**不能直接作为 wire DTO**。
- SMuFL 元数据可由 JSON 构造；Bravura 资源加载与实际绘制字体仍属于平台职责。

### 2.3 迁移阻塞点

1. **构建未验真**：各模块只有 `jvm {}`，没有 `androidTarget`、iOS、`js` 或 `wasmJs`；
   `renderer/commonMain` 使用 context receivers，但编译参数也只配在 JVM target，新增 target 会先暴露此问题。
2. **平台 actual 不完整**：
   - `api`：时钟；
   - `renderer`：Bravura 资源加载、文本测量、读写锁；
   - `theory`：勋伯格禁忌进行资源加载；
   - `audio`：仅 JVM 播放实现。
3. **单 target 掩盖的平台假设**：`StyleOverrideManager` 在 `commonMain` 使用 `synchronized`
   和 `GlobalScope`；迁移时应改为实例拥有的 `CoroutineScope`，并验证锁在 JS/Native 的实现语义。
4. **进程全局状态**：`PluginRegistry`、`ScoreSerializer` 是可变单例。Web Worker、多文档和测试隔离
   更适合显式的 `EngineEnvironment(pluginRegistry, scoreCodec, resources)` 实例。
5. **字体确定性**：布局文字由 JVM AWT 测量，而桌面最终由 Compose/Skia 绘制；跨端 actual
   可能造成换行和碰撞差异。音乐字形继续用 SMuFL 元数据，普通文字需统一字体文件、版本指纹和测量约定。
6. **传输契约缺口**：`RenderCommand` 可序列化，但尚无版本化的冻结乐谱容器；`MidiScore` 也需补
   跨端 round-trip 与 sealed event 判别测试。
7. **YAML 依赖**：`core/commonMain` 直接依赖 Kaml。该项目已归档，维护者只承诺 JVM 完整支持，
   JS/Wasm 仍为高度实验，不能作为 iOS 共享 codec 的基础。应把 kotlinx JSON codec 留在共享层，
   把 YAML 兼容读取拆成可替换的 `ScoreCodec`；选定受维护的跨端 YAML 实现前，桌面/服务端继续兼容
   现有 `.mecon`，新端不可静默丢失 YAML 互操作。
8. **MusicXML 依赖**：xmlutil 是多平台库，但 Native 的 DTD/validation 等高级能力有限；当前
   import/export 子集需用 `test-scores/` 在 JS/iOS 做 fixture round-trip，不能仅以依赖可解析为通过。
9. **工具链版本**：项目仍为 Kotlin 2.1.0 / Compose Multiplatform 1.7.0。加 iOS/Web target 前，
   先单独升级并锁定兼容矩阵，避免把版本迁移故障误判成引擎问题。

## 3. 目标架构

```text
                         ┌─ Desktop Compose/Skia + JVM audio
共享 Kotlin 内核 ────────┼─ Android/iOS Compose + native audio
 api/core/theory         ├─ React + Canvas/Web Audio（Kotlin/JS Worker）
 exploration/renderer   └─ Harmony ArkUI + 定制 K/N bridge（服务端降级）
          │
          └─ FrozenScoreBundle ─ CDN/文件 ─ 高效只读展示与播放
```

### 3.1 共享层

- `api`：数据模型、编辑命令、change set、状态；不得依赖 UI、文件系统或平台线程。
- `core`：Compute、增量编辑、MusicXML、共享 JSON `ScoreCodec`；文件选择/读写与可替换的 YAML
  兼容 codec 留给平台/容器层。
- `theory` / `exploration`：规则、搜索、可序列化 Solver 协议；不含 React/Compose 文案成品。
- `renderer`：布局、几何、命中数据、增量 splice；只依赖显式 `FontMetricsProvider`、
  `ResourceLoader`、取消检查等小接口。
- `audio-model`（可从现有 `audio` 拆出）：`ScoreToMidiConverter`、`MidiScore`、播放时间映射。

### 3.2 平台层

- `apps/desktop`：保留窗口、文件、Skia Picture 缓存、JVM MIDI/FluidSynth。
- `apps/mobile`：Android/iOS 共享自适应 shell、画布与交互状态；相机、文件、MIDI/Audio 各端 actual。
- `apps/web`：React/TypeScript UI、Canvas2D 后端、Web Audio；Kotlin/JS 包只提供引擎 facade。
- `bridge/web`：只导出以字符串/字节为边界的稳定方法，不把内部 Kotlin collection 和类图暴露给 React。
- `apps/harmony`：ArkTS/ArkUI UI 与 Node-API/native bridge；只有 PoC 通过后才依赖本地共享引擎。

跨端入口统一接收 `EngineEnvironment`。平台负责创建 scope、资源与 backend；引擎不得查找
classpath、全局 dispatcher、浏览器全局对象或系统默认字体。

插件按“core 能力”和“平台面板”拆开：和弦分析等 core 可编入各端的内置插件清单，
`plugins/*/desktop` 不进入移动/Web。动态安装第三方插件不是首轮移植目标。

## 4. Web 方案

Kotlin/JS 是首选引擎 target：Kotlin/JS 当前为 Stable，且官方把“共享业务逻辑 + 原生
JS/TypeScript UI”列为 Kotlin/JS 的适用场景；Kotlin/Wasm 与 Compose Web 仍为 Beta，
与 React 的类型互操作也更严格。Wasm 可在性能数据证明 JS 不够时再并行加入，不作为首发前置。

### 4.1 高效展示：冻结几何

> ✅ 已落地首版：`FrozenScoreBundle` 位于 `renderer/.../frozen/`，由 `FrozenScoreProjector` 从
> `RenderResult` 投影，并作为 `geometry/<scoreId>.json` 打包进 `.mecon` 容器（见
> [data_model/mecon-container.md](data_model/mecon-container.md)）。`FrozenSurface` 直接持有已
> `@Serializable` 的 `RenderElement`（含 `RenderCommand` 与命中/事件元数据），连续谱一个 surface、
> 分页谱一页一个。`playback: MidiScore?` 仍为 🚧（音频 backend 一并接入时补）。

新增版本化协议 `FrozenScoreBundle`，而不是序列化 `RenderResult`：

```kotlin
@Serializable
data class FrozenScoreBundle(
    val schemaVersion: Int,
    val engineVersion: String,
    val fontFingerprint: String,
    val bounds: AbsoluteRect,
    val surfaces: List<FrozenSurface>, // 单个连续谱面或多个分页面；各自持有局部 elements
    val timePositions: List<FrozenTimePosition>,
    val playback: MidiScore?,
)
```

- 由桌面、构建服务或 Web Worker 执行 Compute/Layout/Render 后生成；静态内容可压缩后放 CDN。
- ✅ `web/packages/frozen-score` 已实现 `.mecon` zip 读取、Canvas2D/SVG 命令重放、命中测试、
  选中框和可选 React 组件；React 不做乐理或排版。
- ✅ `web/packages/web-renderer` 已实现完整 Kotlin/JS 排版 facade，复用同一个冻结几何浏览器后端；
  Kotlin 边界只接收 `StorageScore` JSON，并返回 `FrozenScoreBundle` JSON。
- `DrawGlyph` 使用随包发布且加载完成后才显示的精确 Bravura 版本；普通文字字体也必须打包并进入
  `fontFingerprint`。字体不匹配时拒绝宣称几何一致，而不是静默 fallback。
- 播放高亮通过 `sourceEventId` 与 `timePositions` 驱动，不重排乐谱。
- SVG 与 Canvas 都是浏览器后端；二者仍以冻结命令为主协议，SVG 不是持久化格式。
- 无障碍层由 React 根据 element metadata 生成稀疏 DOM 描述，Canvas 不单独承担语义。

浏览器包的构建、API 与扩展约定见 [renderer/web-renderer.md](renderer/web-renderer.md)。

### 4.2 简单编辑

- React 维护工具栏、表单、选区和响应式布局；`StorageScore` 是权威文档。
- 编辑命令发送给 Kotlin/JS Web Worker：`applyEdit → incremental compute → layout → frozen patch`。
- 首版允许整段 JSON 返回和全量重排；必须保留 `changeSet` 与 patch 版本字段，后续无需改协议即可优化。
- 同一文档的 worker 串行执行，采用 latest-wins/cancel；React 主线程只绘制、命中和播放。
- `@JsExport` facade 仅使用 `String`、数值、`ByteArray` 等边界，例如
  `describeJson()`、`enumerateJson(request)`、`solveJson(request)`、`renderJson(score, viewport)`。

### 4.3 探索与和弦进行搜索

- 页面启动读取 `SolverApi.describe()` 生成规则树和表单。
- 搜索先调用 `enumerate` 返回符号进行；用户选中后才调用 `solve` 生成 top-K 四部写作与 finding，
  避免为列表中的每项提前排版。
- 求解和排版均在 Worker；结果沿用 `CellOutput`，无需在 TypeScript 重建教材规则。
- “直接试听”由用户手势解锁主线程 `AudioContext`；Worker 返回 `MidiScore`，Web Audio scheduler
  负责 program、note、tempo 和 stop。音色首版用小型 GM SoundFont/sample pack，缓存与版权单独管理。

## 5. Android、iOS 与手机交互

手机与 Pad 的产品范围、窗口布局、触控命中、屏幕钢琴和自由练习流程见
[移动端交互调研与 UI 方案](ui/mobile.md)；本节只定义复用与平台架构。

- 引擎与增量管线直接编入 app 进程，不在每次编辑时跨 JSON/FFI；这样才能保持桌面端的局部重算效率。
- Android 使用 KMP Android target；iOS 输出 framework 并在 macOS CI 构建。两端后台串行执行
  Compute/Layout/Render，UI 仅消费不可变结果。
- 将 `ComposeScoreRenderer` 拆为：
  1. 可共享的 Compose `DrawScope` 命令解释器；
  2. 桌面专属的 classpath 字体与 Skia Picture 分层缓存；
  3. 移动端 viewport/tile cache。
- Android/iOS 可共享 Compose Multiplatform 组件与 view-model，但手机 shell、工具选择、选区手柄、
  手势和输入流程按小屏重新设计；“使用共享 UI 技术”不等于复制桌面交互。
- 音频 backend：Android 用平台低延迟/Media API，iOS 用 AVAudioEngine/AVFoundation；共同消费
  `MidiScore` 与 audition 请求。平台中断、耳机切换、后台策略留在 app 层。

手机的首版范围应是：打开/展示/播放、音符与常见记号的简单编辑、探索模式；复杂页面设置、
插件管理和桌面多面板工作区不进入首版。

## 6. 平板与输入能力模型

平板不单独 fork UI，而由同一应用根据能力组合布局：

| 能力 | 桌面 | 手机 | 平板 |
|------|------|------|------|
| 键鼠/快捷键/hover | 完整 | 外接时可选 | 完整 |
| 触控手势/大命中区 | 辅助 | 主交互 | 主交互 |
| 触控笔压力/悬停/侧键 | 可选 | 可选 | 完整 |
| 多栏与属性面板 | 完整 | sheet/单栏 | 宽屏时复用桌面布局 |

抽出 `InputCapabilities(pointerKinds, hover, keyboard, stylus, viewportClass)` 与统一编辑命令；
UI policy 决定命中半径、手柄、面板和快捷方式。笔迹手势只能产生预览/编辑命令，不能直接修改
Renderer 状态。Apple Pencil/Android stylus 事件由平台适配为统一 pointer sample。

## 7. 鸿蒙策略

### 7.1 案例能证明什么

Android 兼容渠道不等同 HarmonyOS NEXT 原生应用。后者仍没有 Kotlin 官方 target，但已有可参考实践：

- 快手扩展 Kotlin/Native，参考 Linux target 接入 OHOS LLVM/musl，生成 `.so + .h`，以 KSP 生成 Kotlin Node-API wrapper；新增 `ohosArm64` 会要求协程、序列化、I/O 等依赖重发对应 klib，且仍有 GC、调试和包体风险。
- 腾讯 KuiklyUI 已公开 `ohosArm64Main`、OHOS renderer 和 ArkTS 壳，可生成 `libshared.so + libshared_api.h`；其 OHOS 构建固定使用定制 Kotlin `2.0.21-KBA-010` 与 KNOI，Compose 也是改包名的适配分支，并非官方 CMP target。
- 案例索引记录哔哩哔哩因调试、性能和多线程限制由 Kotlin/JS 转向 K/N。此二手信息仅作路线预警，选型以可复现 PoC 和公开源码为准。

结论修正为：**同一份共享源码可通过定制 K/N 编译，但这不是零适配、零维护成本的官方能力。**

### 7.2 Mecon 选型

| 路线 | 适用性 | 决策 |
|------|--------|------|
| 定制 K/N `.so` + Node-API + ArkUI | 保留引擎性能，UI 可按小屏重做 | `H0` 首选 |
| Kotlin/JS 嵌入鸿蒙容器 | 接入快，线程与性能上限较低 | 只作只读/探索过渡验证 |
| KuiklyUI / ovCompose 共享 UI | 适合追求 Compose UI 复用的产品 | 首版不引入；与 ArkUI 重做目标不符 |
| 服务端 Solver + `FrozenScoreBundle` | 无本地工具链风险，离线能力有限 | `H0` 失败时的正式降级 |

鸿蒙建立独立构建入口并锁定定制工具链，不把主构建从 Kotlin 2.1.0 降到案例版本；该入口编译同一份共享源码。先接入 `api + theory + exploration`，再加入去除 Kaml 硬依赖后的 `core`、`renderer`、`audio-model`。三方库逐个标注“已有 OHOS klib / 可从源码增加 target / 必须替换”，禁止用 stub 静默跳过。

`ArkUI → TypeScript declaration → Node-API wrapper → MeconEngine.so → describe/enumerate/solve(JSON)、compute/render(bytes)、cancel/close(handle)`

桥上不暴露 Kotlin collection、Flow 或细粒度对象；实例句柄拥有 solver、scope 和资源，大结果使用 JSON/`ByteArray`。可参考 KNOI/KNAPI 代码生成，但审计版本、发布物和维护策略前不让业务 API 依赖插件注解。ArkUI/Native Canvas 解释 `RenderCommand`，不为单个绘制后端引入整套 Kuikly Compose。

### 7.3 `H0` 退出条件

1. CI 可重复生成 `.so + .h`，真机运行共享 `api + theory` 金标准并正确加载资源。
2. 协程取消、后台线程、ArkTS 回调、异常转换和 `close` 通过压力测试；30 分钟搜索/排版无持续内存增长，native crash 可符号化。
3. `core + renderer` 全量/增量 p50、p95、峰值内存与桌面同口径；Bravura 度量、命令快照通过，包体增量有预算。
4. 固化编译器校验值、Maven 镜像、OHOS SDK/NDK、许可证与负责人，并规定主线 Kotlin 版本落后时的处理策略。
5. 关键依赖或性能、稳定性、可调试性不达标，则采用 ArkUI + 服务端 Solver/`FrozenScoreBundle`，不另写 ArkTS 乐理或排版。

通过 `H0` 后，才把“HarmonyOS NEXT 不重写引擎且支持离线完整编辑”排入确定交付日期。

## 8. 正确性、性能与测试门禁

- 每个共享模块至少在 JVM、JS browser、Android unit 与 iOS simulator 编译；common tests
  不再只由 JVM 执行。
- 同一输入的 `ComputedScore`、符号搜索结果和 `RenderCommand` 做结构快照；跨端浮点使用明确容差，
  文本布局另做固定字体金标准。
- `FrozenScoreBundle`、Solver 请求/结果、`StorageScore`、`MidiScore` 做双向兼容测试，并保留
  `schemaVersion`、未知字段兼容与上一版本 fixture。
- 建立小谱、钢琴谱、管弦总谱、探索 top-K 四组 benchmark。移动端增量编辑不得相对同设备全量路径
  退化；稳定后把 p50/p95 和内存基线提交到仓库。
- Web 只读路径滚动时不得调用 Compute/Layout；主线程不得运行 solve。播放调度在用户手势后启动，
  stop/seek 不得遗留 hanging note。
- 每个 `RenderEngine`、solver run、audio backend 都由文档/session 拥有并可关闭；清除
  `GlobalScope`，取消旧任务后不得发布过期结果。

## 9. 实施顺序

| 阶段 | 工作 | 退出条件 |
|------|------|----------|
| M0 工具链 | 独立升级 Kotlin/Compose/依赖；拆分 Kaml/YAML 风险；记录 JVM 性能与快照基线 | 桌面测试/快照不回退，跨端 codec 决策落地 |
| M1 纯内核 | 给 `api/core/theory/exploration/audio-model` 加 JS、Android、iOS target；补资源/时钟 actual | 四端编译并跑 common tests，JSON codec round-trip |
| M2 排版 | 注入字体度量/资源；清理锁与 scope；新增冻结协议 | 同谱命令快照一致，bundle 可 round-trip |
| M3 Web 只读 | React Canvas + 字体 + Web Audio + CDN bundle | 固定谱无需引擎即可展示/播放 |
| M4 Web 探索/编辑 | Kotlin/JS Worker facade、Solver 搜索、简单编辑 | 搜索可试听，编辑不阻塞主线程 |
| M5 移动 | Android/iOS app、Compose 命令后端、原生音频、小屏交互 | 代表谱性能/稳定性达标 |
| M6 平板 | capability UI、键鼠/触控/笔组合 | 桌面工作流与触控笔均通过手测 |
| H0 鸿蒙 | 定制 K/N `.so`、Node-API、依赖与真机压力 PoC | 决定本地、服务端或 Web 过渡路线 |

M1/M2 必须先于新端大量 UI 开发；否则平台团队会围绕尚未稳定的桥接协议重复返工。

## 10. 外部平台依据

- Kotlin 官方的[平台稳定性表](https://kotlinlang.org/docs/multiplatform/supported-platforms.html)：Android、iOS、Kotlin/JS 为 Stable，Kotlin/Wasm Web 为 Beta。
- Kotlin 官方的[Web 方案选择](https://kotlinlang.org/docs/web-overview.html)：业务逻辑接 JS/TypeScript UI 推荐 Kotlin/JS，共享 Compose UI 才优先 Wasm。
- Kotlin 官方的[JavaScript 导出说明](https://kotlinlang.org/docs/js-to-kotlin-interop.html)：`@JsExport` 可生成 TypeScript 可见 API，但 collection、`Long` 等边界需显式处理。
- Kotlin 官方的[Native target/构建主机清单](https://kotlinlang.org/docs/native-target-support.html)：iOS 最终产物需要 macOS；当前清单不含 OHOS。
- 社区的[KMP 跨平台案例索引](https://github.com/xiaobailong24/kmp-case-studies-cn)汇总国内 HarmonyOS 路线，可作案例入口；README 也注明公开信息可能有偏差。
- 快手的[KMP 鸿蒙落地分享](https://blog.jetbrains.com/wp-content/uploads/2024/12/day1_5-KMP-.pdf)公开 OHOS target、`.so`、Node-API/KNAPI、基础库重编译与已知风险。
- 腾讯的[KuiklyUI 开源实现](https://github.com/Tencent-TDS/KuiklyUI)可复现 `ohosArm64Main`、定制 Kotlin/KNOI 以及 `.so + .h` 到 ArkTS 壳的链路。
- Kaml 的[维护状态与平台说明](https://github.com/charleskorn/kaml)：项目已归档，只有 JVM
  完整支持，JS/Wasm 为高度实验；现有 YAML codec 不能直接视为 iOS 可用。
- xmlutil 的[项目说明](https://github.com/pdvrieze/xmlutil)将其定位为 Kotlin Multiplatform XML
  库；Native 的高级 XML 能力仍需按 Mecon 的 MusicXML fixture 验证。
- 华为的[HarmonyOS 应用开发说明](https://developer.huawei.com/consumer/cn/app/planning)以
  ArkTS/ArkUI 为应用主路径；其[应用开发知识地图](https://developer.huawei.com/consumer/cn/app/knowledge-map/)
  把 NDK 工程、三方 SO 移植和 Node-API 跨语言交互列为原生桥接路径。
