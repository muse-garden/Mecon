# 符杆与符杠

> 路径：`renderer/.../layout/stem/`、`renderer/.../render/BeamGroupElement.kt`
>
> **状态**：✅ 单一符杆方向、单层符杠、混合时值符杠（含 hooks）、✅ 跨谱表符杠（单层）已实现；🚧 折杠（kneed beam）渲染、符杠斜率优化尚未完成。

## 1. 数据流

```
ComputedVoiceEvent (含 BeamInfo)
        │
        ▼
StemDirectionResolver           ← 用户指定 / 多声部规则 / 平均位置
        │
        ▼
VoiceEventLayoutBuilder         ← 计算符杆 topY/bottomY，含 beam extension
        │
        ▼
BeamLayoutComputer              ← 按 BeamInfo 推 beam 段、hook、层级
        │
        ▼
BeamGroupElement.render()       ← 输出 RenderElement (DrawPath)
```

`BeamInfo` 来源于 `ComputedScore`（见 [../data_model/computed.md](../data_model/computed.md) 第 2 节），`totalBeamCount / beamsLeft / beamsRight` 已经决定每个音符的 beam 拓扑，渲染层无需再重新分组。

自动分组先解析完整 `TupletSpan`，再应用拍号的普通 beat group：未带显式 `RenderingProps.beaming` 的连音组内所有可加符杠成员共用一个 `BeamGroupId`，因此跨越普通拍组的六连音也不会拆成 `3+3`。显式符杠设置仍优先；休止符不生成 `BeamInfo`。

## 2. 符杆方向规则（优先级降序）

1. **用户显式指定** (`StoragePropsRendering.stemDirection`) — 最高优先
2. **多声部** — 同一谱表存在多于一个声部时，奇数声部 UP，偶数声部 DOWN
3. **符杠组内一致** — 同一 `BeamGroupId` 的所有音符方向必须相同
4. **单音符** — 平均 `staffPosition > 0` 取 DOWN，否则 UP（中线 `=0` 视为 UP）

`StaffPosition` 定义参考 [../data_model/computed.md](../data_model/computed.md)：中线 = 0，向上为正，`> 5` 或 `< -5` 需要加线。

## 3. 折杠 (Kneed Beam) 检测 🚧

当一组事件同时跨越上下加线区域且谱表内无音符时，`StemDirectionResolver` 会返回 `SplitBeamResult(isSplit = true)`：

```kotlin
if (notesAboveStaff.isNotEmpty()
    && notesBelowStaff.isNotEmpty()
    && middleNotes.isEmpty()
) { /* split */ }
```

**当前未实现**：
- 折杠几何（上下两段 beam 在中间汇合）
- 折杠组内的独立符杆方向

短期可绕过：手动指定 `userStemDirection`。

## 4. 符杠几何

`BeamGroupElement` 渲染策略：

- **贯穿符杠**（through）：当前音符 `beamsLeft & beamsRight` 同层均存在 → 跨过该位置
- **起始符杠**：`beamsLeft = 0 && beamsRight > 0`
- **结束符杠**：`beamsLeft > 0 && beamsRight = 0`
- **钩子**（hook）：仅一侧延伸，宽度约 `0.5–1.0` staff space

每层符杠的 Y 偏移：

```kotlin
val levelOffset = if (stemUp)
    StaffSpace( level * (beamThickness + beamSpacing))
else
    StaffSpace(-level * (beamThickness + beamSpacing))
```

形状是**两端垂直**的平行四边形（不是矩形），因此可以无缝接到不同长度的符杆上。

## 5. 简化的斜率算法

```kotlin
val idealSlope  = (lastTipY - firstTipY) / (lastX - firstX)
val damped      = idealSlope / (1 + damping)            // damping = 1 → 减半
val finalSlope  = if (abs(damped) < roundToZeroSlope) 0f else damped
val beamY       = computeBeamBaseY(events, dir, finalSlope) // 保证最短符杆 ≥ 最小值
```

未实现的优化（来自 LilyPond）：内部音符凹凸约束、与谱线对齐 (beam quantization)、碰撞惩罚。

## 5.1 最小符杆长度约束

`BeamLayoutComputer.compute()` 在以平均 intercept 定位 beam 之后，会按方向对每个音符强制最小符杆长度，避免大跨度 beam 组中极端音符的符杆被截短或符杠侵入音符头：

```kotlin
// UP：beam 须在所有 nearNotehead 上方至少 minStemLength
val maxAllowedStartY = constrainingNotes.minOf { note ->
    note.nearNoteheadY.value - minStemLength - slope * (note.x - first.x)
}
if (startY > maxAllowedStartY) startY = maxAllowedStartY
// DOWN 对称
```

`BeamNoteInput.nearNoteheadY` 是该音符靠近 beam 一侧的符头 Y（UP 取顶部符头，DOWN 取底部符头），由 `BeamGroupProcessor` 通过 `defaultTipY ± (stemLength + beamedStemExtension)` 反推。

**`minStemLength` 必须考虑多层符杠堆叠**。`BeamLayoutComputer` 计算的 `startY` 是 beam 中心线（更确切地说是最外层 beam 的中心），内层符杠向符头方向堆叠会蚕食可用净距。`BeamGroupProcessor` 按组内最大 beam 数计算，并按组的 `NoteScale` 统一缩放：

```kotlin
val scale      = noteScale.value                               // 来自 BeamGroupRenderData
val beamThickness = config.engravingDefaults.beamThickness.value * scale
val beamSpacing   = config.engravingDefaults.beamSpacing.value   * scale
val innerStack = (maxBeamCount - 1) * (beamThickness + beamSpacing)
val minStemLength = config.beamLayoutConfig.minimumFreeLength(maxBeamCount) * scale +
    innerStack + beamThickness / 2f
```

`minimumFreeLength` 是配置中的原始净距（未缩放），乘以 scale 后与已缩放的 `innerStack`、`beamThickness/2` 相加，避免双重缩放。例如 16 分音符（2 层 beam，scale=1）下 `minStemLength ≈ 1.5 + 0.73 + 0.24 ≈ 2.47` staff space，装饰音（scale=0.6）约为 `1.48`。如果忽略 `innerStack`，宽音域琶音中极端音符的符杠会与符头重叠。

`NoteScale` 同时影响 `idealStemSpan`（反推 `nearNoteheadY` 时使用）：

```kotlin
val idealStemSpan = (config.stemLength + config.beamedStemExtension) * scale
```

关于 `NoteScale` 的完整说明见 [grace-notes.md §4](grace-notes.md)。

## 5.2 跨谱表符杠（Cross-Staff Beam）

当一个符杠组的音符渲染在**多个谱表**上（某些音符带 `RenderingProps.crossStaffOffset`），符杠落在两谱表之间：

- **符杆方向**（`StemDirectionResolver`）：组内音符按所在谱表交错——上方谱表音符 `DOWN`、下方谱表音符 `UP`，符杆一律指向中间的符杠（符杆交错 / 符杆交错排列）。该判定按 `VoiceContext.staffIndex` 逐音进行，覆盖单一方向的常规规则。
- **符杠几何**（`BeamGroupProcessor.buildCrossStaffBeam`）：在两谱表 `centerY` 的中点放一条**水平**基线，每个音符的符杆从各自谱表的符头伸到该基线（记入 `stemAdjustments`）。该步在谱表 Y 确定之后运行，因此能取到真实的谱表间距。
- **被借用谱表的声部插入**：借用音符在目标谱表的对应时段被视为新增声部——借上方（`offset<0`）作为下方/第二声部（符杆向下），借下方（`offset>0`）作为插入的第一声部（符杆向上）。`EventCollector` 把借用音符登记到目标谱表的多声部集合，使目标谱表本身的音符也按多声部取符杆方向。

当前限制：基线为水平、仅适配单层符杠（8 分音符跨谱表）；多层符杠堆叠方向与符杠斜率为后续工作。借用音符的符杆长度按目标谱表内的默认值参与谱表间距估算，极紧排布下可能需要二次微调。

## 6. 关键配置

```kotlin
data class BeamLayoutConfig(
    val beamThickness: Float = 0.48f,
    val damping:       Float = 1f,
    val roundToZeroSlope: Float = 0.02f,
    val autoKneeGap:   Float = 5.5f,        // 触发折杠检测
    val beamedLengths: List<Float> = listOf(3.26f, 3.5f, 3.6f),
    val beamedMinimumFreeLengths: List<Float> = listOf(1.83f, 1.5f, 1.25f),
    val neutralDirection: StemDirection = DOWN,
)
```

数值参考 LilyPond `beam-interface`。

## 7. 待办（精要） 🚧

- 折杠几何与渲染（检测已就绪）
- 完整斜率优化（凹凸约束、与谱线对齐、碰撞惩罚）
- 跨谱表符杠斜率与多层符杠堆叠（单层已实现，见 §5.2）
- French beaming 风格（中间符杆不触及符杠）
- 符杠/临时记号碰撞处理

设计指引：保持 `BeamGroupElement` 输入纯化（`BeamInfo + 解析后符杆方向`），把斜率/层级算法收敛在 `BeamLayoutComputer` 内独立测试。
