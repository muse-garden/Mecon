# 插件系统

> **状态**：✅ 注册框架已落地；和弦分析与乐理分析桌面插件已接入右侧面板。

插件以独立 Gradle 子模块的形式存在，通过 `:api` 暴露的 SPI 注册数据轨道、注释谱表与桌面面板，**不直接依赖** `:core` / `:renderer`。

## 1. 文档索引

| 文档 | 内容 |
|------|------|
| [plugin-framework.md](plugin-framework.md) | 插件注册：`MeconPlugin` / `PluginRegistry` / 各类 Provider |
| [custom-track.md](custom-track.md) | 自定义 Plugin Track：Storage / Runtime / Computed 三层事件 + 多态序列化 + CalcBuilder |

## 2. 模块结构

```
plugins/
├── chord-analysis/
│   ├── core/      （KMP，无 UI）—— 事件类型、计算、AnnotationStaffProvider、MeconPlugin
│   └── desktop/   （JVM-Compose）—— 右侧面板 UI、桌面插件入口
└── theory-analysis/
    └── desktop/   （JVM-Compose）—— 乐理分析面板，当前消费 `:theory` 的固定声部视图
```

Gradle 项目名后缀避免与 `:core` / `:apps:desktop` 的 capability 冲突（见 `plugins/chord-analysis/*/build.gradle.kts` 中显式设置的 `group`）。

## 3. 三类扩展点

| 扩展点 | 接口 | 注册方法 | 所在模块 |
|--------|------|----------|---------|
| 多态事件序列化 | `StoragePluginEvent` 子类 | `ctx.registerEventSerializer(...)` | `:api` |
| 注释谱表（文字 / Glyph） | `AnnotationStaffProvider` | `ctx.registerAnnotationStaffProvider(...)` | `:api` |
| 音符样式 | `NoteStyleProvider` | `ctx.registerNoteStyleProvider(...)` | `:api` |
| 所选符头临时标签（不占排版） | `NoteSelectionLabelProvider` | `ctx.registerNoteSelectionLabelProvider(...)` | `:api` |
| 桌面右侧面板 | `PluginPanel` | `ctx.registerPanelDescriptor(...)` | `:apps:desktop-ui-kit` |

通用渲染叠加层 `PluginRenderComponent` 仍保留作为逃生口，但**首选**通过 `AnnotationStaffProvider` 抽象（坐标自动处理，不会直接接触 `CoordinateTransformer`）。

`AnnotationStaffProvider.pluginTrackTypes` 为空时表示 provider 不依赖存储插件轨道，适用于乐理分析这类从全谱计算 annotation 的插件；非空时 renderer 仍会在乐谱不含对应 track type 时跳过该 provider。`NoteStyleProvider.pluginTrackTypes` 也采用同一语义。

## 4. 启动注册

桌面端在 `apps/desktop/.../bootstrap/PluginBootstrap.kt` 调用：

```kotlin
fun bootstrapPlugins() {
    PluginRegistry.installAll(listOf<MeconPlugin>(
        ChordAnalysisDesktopPlugin(),
        // ...其他插件
    ))
    ScoreSerializer.installSerializersModule(PluginRegistry.buildSerializersModule())
}
```

必须在 `application {}` 与首次读写乐谱之前调用，否则多态序列化注册不完整。

## 5. 依赖原则

```
:plugins:foo:core    → :api, :theory
:plugins:foo:desktop → :plugins:foo:core, :apps:desktop-ui-kit, :api
:apps:desktop        → 上述全部 + 直接依赖 :plugins:foo:core（编译期需要走通继承链）
```

纯桌面展示型插件可以没有 `core` 模块，例如 `:plugins:theory-analysis:desktop` 只注册面板、annotation staff、note style provider 与 i18n，不写 PluginTrack，也不参与多态序列化。

插件状态写入仍需经由 `GlobalScoreState.activeManager.updatePluginTrackState()`，不绕过状态管理器直接改 `RuntimeScore`。
