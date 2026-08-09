# 演奏记号 (Articulations)

> 状态：✅ 可用（Staccato / Spiccato / Tenuto / Accent / Marcato，可叠加）

## 1. 数据来源

演奏记号是**音乐信息**，记在 `StoragePitchEvent.articulations: List<Articulation>`（随和弦整体，不区分单个音）。
"画在符头侧还是符尾侧"是**排版选择**，记在 `RenderingProps.articulationPlacement`（`AUTO`/`NOTEHEAD`/`STEM`，默认符头侧）。

`ComputeEngine` 通过 `ComputedVoiceEvent.from` 把两者分别透出为 `ComputedVoiceEvent.articulations` 与 `articulationPlacement`——Computed 层只决定"有哪些记号"，不算坐标。

`Articulation` 枚举：`STACCATO / SPICCATO / STACCATISSIMO / TENUTO / ACCENT / MARCATO / FERMATA`
（FERMATA 由其它居中逻辑处理，不在本通道绘制）。

## 2. SMuFL glyph 映射（U+E4A0–E4BF）

| Articulation | above | below |
|---|---|---|
| STACCATO | `articStaccatoAbove` E4A2 | E4A3 |
| TENUTO | `articTenutoAbove` E4A4 | E4A5 |
| ACCENT | `articAccentAbove` E4A0 | E4A1 |
| MARCATO | `articMarcatoAbove` E4AC | E4AD |
| SPICCATO / STACCATISSIMO | `articStaccatissimoAbove` E4A6 | E4A7 |

映射与堆叠顺序集中在 `render/ArticulationGlyphs.kt`（纯函数，便于单测）。

## 3. 排版（`ArticulationLayoutComputer`）

仿 `SlurLayoutComputer`：用 `voiceEventLayouts` + `timeSlotMap` 解析出每个事件的 `NoteElement` / `VoiceEventLayout` / `StaffLayoutInfo`。

- **侧别**：`STEM` → 符杠尖端那一侧；否则符头侧（与符杠相反，stem-up→下，stem-down→上，无符杠→下）。
- **挨着符头，不强制移出五线谱**：记号紧贴符头（`articulationNoteGap`）。符头侧若某记号会压在谱线上，则沿外侧最小幅度挪到相邻空档（`articulationLineClearance`），尤其避免 tenuto 横杠与谱线重合；谱外的记号不动，保持贴近音符。
- **堆叠**：多记号由内向外 `staccato/spiccato/tenuto → accent → marcato`，逐个按 glyph 高度 + `articulationStackSpacing` 推进，每个再做一次避线处理。
- **水平**：符头侧居中于符头列；符杠侧对齐符杠 X。
- 坐标已折叠槽位 X 与谱表 centerY（同 `SlurLayout`），由 `ArticulationElement` 直接出 glyph 命令，并注册 `VoiceArticulationSection` 供拾取。

配置项见 `RenderLayoutConfig.articulation*`。

## 4. 与 Slur 的避让

`RenderEngine` 在符杆/符杠之后、连音线之前算出 `Map<EventId, ArticulationLayout>`，传给 `SlurLayoutComputer`：

- **跨越**：`collisionRequiredApex` 把中间音符的 articulation 外缘一并纳入抬高计算。
- **端点**：起/止音若在弓向同侧有记号，端点 Y 外推到记号外缘 + `slurVerticalGap`，避免弧线压住 staccato 点等。

## 4.5 持久化几何（overlay）

articulation 几何可持久化到 `StorageScore.geometry`（[../data_model/storage.md §1.3](../data_model/storage.md)）：

- `computeArticulationLayouts` 接受可选 `geometry: ScoreGeometry?`。事件在 overlay 中有条目（键 = voice `EventId`）
  → `GeometryProjector.resolveArticulation` 用**当前符头/符干列 X + 谱表中线 + 存储 Y 偏移**
  （`MarkOffset(index, above, dx, dy)`）重建 `ArticulationLayout`，glyph 与 bounds 按字体度量复算；X 始终按
  当前音符重新居中，避免水平避让后沿用旧槽位偏移。无条目 → 回退现有自动 `buildLayout`。
- overlay 的 mark 索引必须完整匹配事件当前全部可绘制演奏法；新增、删除或换序导致不匹配时，整组缓存失效并
  回退自动排版，防止旧的单 mark 缓存吞掉后来追加的演奏法。
- `GeometryProjector.toStored` 把每个 mark 原点折成 `(dx=origin.x−slotX, dy=origin.y−centerY)`。
  捕获→重解析为亚像素恒等（`RenderGeometryOverlayTest`）。
- 锚点（当前符头/符干列 X、谱表中线）稳定，故 articulation 几何极少需随编辑改动。
- **增量失效（Phase 2，已落地）**：articulation 的自动排版只依赖**其自身事件**（符头位置、符杆侧、记号列表、
  槽位 X），不看邻居 → 某条目 **stale（剔除→自动重排）** ⟺ 其事件在 `ComputeChangeSet.touchedEvents`；否则
  按锚点自动跟随、**按引用复用**。判定见 [incremental-rendering.md](incremental-rendering.md)。

## 5. 相关文件

- `api/storage/RenderingProps.kt`（`Articulation` / `ArticulationPlacement`）、`storage/events/StorageEvents.kt`
- `renderer/render/ArticulationGlyphs.kt` / `ArticulationLayoutComputer.kt`、`layout/ArticulationLayout.kt`、`elements/ArticulationElement.kt`
- `renderer/render/SlurLayoutComputer.kt`（避让）
