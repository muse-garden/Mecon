# 架构概览

Mecon 是一款基于 Kotlin Multiplatform 的专业音乐分析应用。当前以桌面端（Compose for Desktop）为主，架构为后续跨平台扩展预留了扩展点。

## 1. 技术栈

| 层次 | 技术 |
|------|------|
| 框架 | Kotlin Multiplatform (KMP) 2.1.0 |
| UI | Compose for Desktop (JVM)；React + Canvas/SVG (Web) |
| 数据序列化 | `kotlinx.serialization` + `kaml` (YAML) |
| 不可变集合 | `kotlinx.collections.immutable` |
| 异步 | Kotlin Coroutines + Flow |
| 音频 | JVM Sound API / MIDI (Desktop) |
| 构建 | Gradle 8.x + Version Catalogs |

## 2. 模块划分

```
api/                       核心数据接口（四层模型 + 交互 API + Plugin SPI）
core/                      计算引擎（ComputeEngine）+ MusicXML + ScoreSerializer
renderer/                  排版 + 渲染引擎（含注释谱表）
audio/                     音频播放接口 + JVM 实现
performance-input/         平台无关的键位、演奏事件、时钟、量化与 take 模型
theory/                    乐理库（骨架）
features/score-editing/    跨端编辑协议、会话、历史与选择语义
bridge/web-engine/         Kotlin/JS 字符串 facade（共享编辑与完整排版）
apps/desktop/              桌面应用入口（Compose UI 主壳）
web/apps/free-practice/    React 轻量壳层 + Worker
web/packages/              冻结命令重放与 Kotlin/JS npm 包装
apps/desktop-ui-kit/       桌面 UI 共享库（主题 / i18n / 面板 SPI），插件可消费
plugins/<name>/core/       插件 KMP 内核（无 UI）
plugins/<name>/desktop/    插件桌面 UI 模块
```

依赖方向（单向）：

```
apps/desktop → renderer → api
apps/desktop → audio    → api
apps/desktop → performance-input → api
apps/desktop → theory   → api
apps/desktop → apps/desktop-ui-kit → api
apps/desktop → plugins/*/desktop → plugins/*/core → api, theory
apps/desktop → features/score-editing → core → api
web/apps/free-practice → bridge/web-engine → features/score-editing, renderer
core         → api
```

插件 core 只依赖 `:api` / `:theory`；插件 desktop 加 `:apps:desktop-ui-kit`。`:core` / `:renderer` 私有类型对插件不可见。注意：`:apps:desktop` 同时直接依赖 `:plugins:*:core`，否则 Kotlin 编译器走不通继承链 `XxxDesktopPlugin → XxxPlugin → MeconPlugin`。

## 3. 数据流

```
磁盘 (.mecon / MusicXML)
     │  ScoreFileService.loadAuto()
     ▼
StorageScore        — 序列化层（YAML，ID 引用）
     │  RuntimeScore.fromStorage()
     ▼
RuntimeScore        — 内存层（对象引用，TimeIndexedList 查询）
     │  ComputeEngine.compute()
     ▼
ComputedScore       — 派生层（临时记号、BeamInfo、Staff位置等全量物化）
     │  ScoreLayoutEntry.computeLayout()
     ▼
UnifiedLayoutResult — 几何层（时间槽 X、事件相对坐标、谱表 Y）
     │  RenderEngine.renderUnified()
     ▼
RenderResult        — 渲染命令 + SectionIndex + HierarchicalSpatialIndex
     │  ComposeScoreRenderer.render()
     ▼
Compose Canvas
```

各阶段的线程归属（IO / Compute / Render 均在后台，UI 线程只负责重组与绘制）见 [threading.md](threading.md)。

## 4. 关键设计原则

### Computed 层决定元素，Renderer 负责排版

`ComputeEngine` 决定**是否**生成 `ComputedBarline / ComputedClef / ComputedKeySignature`；Renderer 直接消费，不重新推导。

### 不可变数据 + 结构共享

Storage / Runtime / Computed 全部使用 `data class`（`val` 字段）。`TimeIndexedList` / `StorageScore` 更新返回新实例，依靠 B+ 树结构共享保持 O(log n) 代价。

### 状态管理集中于 ScoreStateManager

用户编辑通过 `commitNewState()` 进入 50 项撤销栈；插件写入通过 `updatePluginTrackState()` 就地更新（不入撤销栈）。

### 乐谱编辑共享会话，Web 只做轻量壳层

桌面、Web 与后续移动端把平台输入转换为同一 `ScoreEditIntent`，统一交给
`features/score-editing` 的 `ScoreEditingSession`。Web React 只负责文件/恢复、控件、命中、
pointer/键盘/MIDI 适配和瞬时 preview；持久化编辑在 Worker 中经 Kotlin/JS facade 执行，Web
不得直接改写 `StorageScore` 或复制音乐规则。详细接入步骤与跨端门禁见
[score-editing-multiplatform.md](score-editing-multiplatform.md)。

桌面普通记谱入口已收敛到共享 intent/session；插件、配器、缩谱等其他文档域仍可使用各自明确的
提交入口，但不得作为新增记谱旁路。Web 的完整编辑器和自由练习共用
`web/packages/web-renderer/editor` 的受控 `ScoreEditor`；自由练习再由 `FreePracticeSession` 组合
内层 score session，统一 workspace/score 历史、复音校验与 typed 投影。接入规则见
[自由练习功能扩展指南](exploration/free-practice-extension-guide.md)。

### 声明式样式系统

交互着色不命令式修改渲染命令，而是通过 `StyleTrack`（按 priority）声明覆盖，`StyleOverrideManager` 合并后推出 `StyleSnapshot`。

## 5. 目录结构（当前实现）

```
api/
├── primitive/      Fraction, TimeCode, Pitch, Duration, Interval, ...
├── storage/        StorageScore, StorageEvent, StorageTrack
├── runtime/        RuntimeScore, TimeIndexedList, RuntimeEvent
├── computed/       ComputedScore, ComputedTypes, CalcBuilder
├── interaction/    EventSection, StyleTrack, StyleRegistry, StyleOverride
├── plugin/         MeconPlugin / PluginRegistry / AnnotationStaffProvider / NoteStyleProvider / ...
└── state/          ScoreState, ScoreStateManager, GlobalScoreState

core/
├── engine/         ComputeEngine（全量重算）
└── musicxml/       MusicXmlConverter（import / export）

renderer/
├── geometry/       StaffSpace, Pixels, Points, Shapes, Curves
├── smufl/          BravuraFont (expect/actual loader)
├── elements/       LayoutElement 子类（音符、休止、谱号等）
├── layout/         UnifiedLayoutComputer, ProportionalLayout, ...
├── render/         RenderEngine（调度）, RenderResultAssembler, StructuralElementRenderer, CoordinateTransformer, HitTestService
│   └── spatial/    HierarchicalSpatialIndex
└── plugin/         PluginRenderComponent, ChordTextRenderComponent

audio/
├── commonMain/     AudioEngine 接口
└── jvmMain/        JvmAudioEngine (javax.sound.midi)

theory/
└── commonMain/     Chord, Scale, Key（骨架）

features/score-editing/
└── src/commonMain/ ScoreEditIntent / ScoreEditingSession / selection / effect

bridge/web-engine/
└── src/commonMain/ Kotlin/JS renderer 与 score editor 字符串 facade

web/
├── apps/free-practice/ React 壳层、Worker、文件恢复与平台输入 adapter
└── packages/          frozen-score 命令重放、web-renderer facade 包装

apps/desktop/
└── src/main/
    ├── App.kt
    ├── Main.kt                         # 调用 BuiltinStrings.install() + bootstrapPlugins()
    ├── bootstrap/PluginBootstrap.kt    # PluginRegistry.installAll + ScoreSerializer.install
    ├── i18n/BuiltinStrings.kt          # 内置 i18n，调用 I18nRegistry.register
    ├── service/                        # ScoreFileService
    └── ui/                             # TopBar, ScoreView, RightPanel, ...

apps/desktop-ui-kit/
└── src/main/kotlin/com/mecon/desktop/uikit/
    ├── theme/        MeconColors / MeconDimensions
    ├── i18n/         I18nRegistry / i18n() / Language
    ├── components/   CollapsiblePanelItem / ResizablePanelItem / Deferred*ResizeHandle / ...
    └── plugin/       PluginPanel / PluginPanelContext / PluginPanelDescriptor

plugins/chord-analysis/core/
└── src/commonMain/kotlin/com/mecon/plugins/chord/
    ├── StorageChordEvent.kt   @Serializable @SerialName("mecon.chord_analysis.chord")
    ├── RuntimeChordEvent.kt / ComputedChordEvent.kt
    ├── ChordCompute.kt
    ├── ChordAnnotationProvider.kt   AnnotationStaffProvider
    ├── ChordToneAnalysis.kt         alignLe 判断每个音符是否为和弦内音
    ├── ChordToneStyleProvider.kt    NoteStyleProvider（isEnabled 开关）
    └── ChordAnalysisPlugin.kt       MeconPlugin（serializer + annotation + style providers）

plugins/chord-analysis/desktop/
└── src/main/kotlin/com/mecon/plugins/chord/desktop/
    ├── ChordAnalysisPanel.kt              PluginPanel + Tonnetz 占位
    ├── ChordSymbolParser.kt
    ├── ChordAnalysisStrings.kt            i18n 注册
    └── ChordAnalysisDesktopPlugin.kt      extends ChordAnalysisPlugin

plugins/theory-analysis/desktop/
└── src/main/kotlin/com/mecon/plugins/theory/desktop/
    ├── TheoryAnalysisPanel.kt             PluginPanel，当前承载固定声部分析
    ├── TheoryAnalysisStrings.kt           i18n 注册
    └── TheoryAnalysisDesktopPlugin.kt     MeconPlugin（纯桌面面板）
```

## 6. 计划扩展

以下目标模块尚未加入 `settings.gradle.kts`；移植前先让现有共享模块通过对应 target 编译：

```
apps/android, ios         — 后续移动端应用与绘制/音频 backend
apps/harmony              — ArkUI 外壳与待验证的本地引擎 bridge
```

排版与乐理复用审计、冻结几何协议、移动/平板交互和鸿蒙风险门禁见
[multiplatform-porting.md](multiplatform-porting.md)；优先级见 [roadmap.md](roadmap.md)。
