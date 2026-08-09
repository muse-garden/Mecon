# 渲染引擎 (Renderer)

> 模块路径：`renderer/src/commonMain/kotlin/com/mecon/renderer/`
>
> **状态**：✅ 主体功能可用（单页布局 + Compose 渲染 + 命中拾取 + 样式覆盖）

渲染引擎只负责**排版与绘制**：把 `ComputedScore` 中已经决定好的事件转换为坐标、几何图形与渲染命令。所有"是否生成元素"的判断都属于上游 `ComputeEngine` 的职责。

## 1. 文档索引

| 文档 | 内容 |
|------|------|
| [layout.md](layout.md) | 统一布局：`ScoreLayoutEntry` / `UnifiedLayoutResult` / 比例间距 / 同槽多声部避让 |
| [render-engine.md](render-engine.md) | `RenderEngine` 拆分现状、协作者职责、下一步瘦身路线 |
| [incremental-rendering.md](incremental-rendering.md) | 增量布局、连续/分页 splice、页缓存、流式输出与性能契约 |
| [traversal-audit.md](traversal-audit.md) | 遍历热点、局部化边界、索引与剩余 TODO |
| [coordinate-system.md](coordinate-system.md) | 双坐标系、SMuFL Y 轴翻转、`CoordinateTransformer` |
| [stem-and-beam.md](stem-and-beam.md) | 符杆方向解析、符杠位置、折杠（部分实现） |
| [ties-and-slurs.md](ties-and-slurs.md) | 连音线（含 let-ring）渲染；slur 复用同一几何（🚧 未接线） |
| [articulations.md](articulations.md) | 演奏记号（Staccato/Spiccato/Tenuto/Accent/Marcato）：侧别、堆叠、slur 避让 |
| [tuplets.md](tuplets.md) | 连音组（三连音 / N 连音）：bracket / slur / number 三种显示样式 |
| [dynamics.md](dynamics.md) | 力度记号 / 渐强渐弱：谱表附着符号抽象、字符拼接、箭头绘制、纵向占位 |
| [grace-notes.md](grace-notes.md) | 装饰音：TimeCode 编码、`GraceNoteInfo` 元数据、MIDI 时窗、缩放与 tie 规则 |
| [spatial-index.md](spatial-index.md) | 层次化空间索引、`HitTestService` 线程安全拾取 |
| [interaction.md](interaction.md) | `EventSection` / `StyleTrack` / `StyleSnapshot` 声明式样式系统 |
| [pdf-export.md](pdf-export.md) | 矢量 PDF 导出：重放冻结几何、AWT 轮廓填充、设计像素→纸面点 CTM |
| [web-renderer.md](web-renderer.md) | Web npm 包：冻结几何与完整 Kotlin/JS 引擎、SVG/Canvas/React |

注释谱表（Annotation Staff）由插件通过 `AnnotationStaffProvider` 提供数据，渲染器在 `UnifiedLayoutComputer` 之后再跑一遍 `AnnotationStaffLayoutComputer`，最终经 `AnnotationStaffRenderer` 输出文字。详见 [../plugin/plugin-framework.md](../plugin/plugin-framework.md)。

## 2. 渲染管线

```
RuntimeScore  ──ComputeEngine──▶  ComputedScore
                                       │
                                       ▼
                          ScoreLayoutEntry.computeLayout()
                                       │
                                       ▼
                            UnifiedLayoutResult
                                       │
                                       ▼
                          RenderEngine.renderUnified()
                                       │
                                       ▼
                         RenderResultAssembler
                ┌──────────────────────┼─────────────────────┐
                ▼                      ▼                     ▼
         RenderResult         HierarchicalSpatialIndex   SectionIndex
       (RenderElements)       (随 RenderResult 持有)   (Section ↔ Element)
                │                      │                     │
                └──────────────┬───────┴─────────────────────┘
                               ▼
                       ComposeScoreRenderer
                  (Canvas DrawScope, 应用 StyleSnapshot)
```

每一步的细节展开在对应章节。

## 3. 模块结构（精简）

```
renderer/
├── geometry/         # StaffSpace, Pixels, Points, Shapes, Curves
├── smufl/            # BravuraFont (expect/actual loader)
├── enums/            # ClefType / NoteheadType / StemDirection / ...
├── elements/         # LayoutElement & 子类（音符、休止、谱号、调号、拍号、小节线等）
├── layout/           # 统一布局算法
│   ├── ScoreLayoutEntry.kt
│   ├── UnifiedLayoutComputer.kt        # 主编排，context(BravuraFont)
│   ├── EventCollector.kt
│   ├── ProportionalLayoutComputer.kt
│   ├── HorizontalSpacingComputer.kt
│   ├── MultiVoiceSlotCollisionResolver.kt
│   ├── StaffLayoutComputer.kt
│   ├── UnifiedTimeSlot.kt
│   ├── VoiceEventLayout(Map|Builder).kt
│   └── stem/                           # StemDirectionResolver / BeamLayoutComputer
├── render/
│   ├── RenderEngine.kt                 # 主入口 + 增量调度，context(BravuraFont)
│   ├── RenderResultAssembler.kt        # RenderResult / SectionIndex / SpatialIndex 装配
│   ├── FullScoreRenderer.kt            # 全量渲染编排
│   ├── NotationElementPassRenderer.kt  # time-slot / stem / flag 通用 pass
│   ├── ContinuousRenderSplicer.kt      # 连续单系统增量 splice
│   ├── PaginatedRenderSplicer.kt       # 分页行级增量 splice
│   ├── RenderElementCollector.kt       # ElementRenderOutput → RichElement
│   ├── StructuralElementRenderer.kt    # 谱线 / 系统结构线 / 谱表头
│   ├── TitleBlockRenderer.kt / RenderPageBuilder.kt
│   ├── AnnotationStaffRenderer.kt      # 注释谱表渲染
│   ├── RenderCommand / RenderElement / RenderResult
│   ├── CoordinateTransformer.kt
│   ├── HitTestService.kt
│   └── spatial/                        # HierarchicalSpatialIndex 等
├── interaction/
│   ├── SectionIndex.kt
│   └── StyleOverrideManager.kt
├── layout/
│   └── AnnotationStaffLayoutComputer.kt  # 注释谱表布局
└── plugin/
    └── PluginRenderComponent.kt          # 通用叠加层（逃生口，无内置实现）
```

## 4. 快速开始

```kotlin
context(bravuraFont) {
    // 1. 计算布局
    val layout = ScoreLayoutEntry.computeLayoutWithComputed(
        runtimeScore = score,
        pageWidth = StaffSpace(100f),
    )

    // 2. 渲染
    val engine = RenderEngine.default()
    val result = engine.render(score)         // 内部：computed → layout → render

    // 3. Compose 绘制
    Canvas(modifier = Modifier.fillMaxSize()) {
        composeRenderer.render(
            drawScope = this,
            result = result,
            styleSnapshot = engine.getStyleOverrideManager().snapshotFlow.value,
            sectionIndex = result.sectionIndex,
        )
    }

    // 4. 拾取（对当前显示的 RenderResult 做命中，索引随结果走、与绘制同源）
    val hitResult = result.hitTest(absolutePoint)
    val voiceEvent = hitResult.allSections().filterIsInstance<VoiceEventSection>().firstOrNull()
}
```

## 5. 关键设计要点

1. **`context(BravuraFont)`**：布局与渲染的入口都通过 Kotlin context receivers 传入字体度量，避免显式参数透传。
2. **负 `relativeX`**：每个 `voiceEvent` 的 X 坐标是相对其时间槽**右端**的偏移（负值），便于和弦右对齐与 staff 间共用槽。详见 [layout.md](layout.md)。
3. **职责单一**：渲染层从 `ComputedScore` 读取 `ComputedBarline / ComputedClef / ...`，**不**自行判断"该不该插入小节线"。
4. **空间索引建在乐谱坐标上**：拾取走 `Pixels → StaffSpace → System/Measure/Staff` 链路，不耦合屏幕分辨率。
5. **样式系统声明式**：UI 与插件各自申请 `StyleTrack(priority)`，提交后管理器合并出 `StyleSnapshot` 推送给渲染层。
