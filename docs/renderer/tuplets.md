# 连音组渲染 (Tuplets)

> 源文件：
> - `renderer/.../layout/TupletLayout.kt`
> - `renderer/.../render/TupletLayoutComputer.kt`
> - `renderer/.../elements/TupletElement.kt`

连音组（三连音、五连音、N 连音）的"是否存在 / 多少音 / 显示哪种样式"由 [Computed 层](../data_model/computed.md) 的 `TupletComputer` 解析为 `ComputedTupletInfo`；渲染器只负责把该信息排版成 bracket / slur / number。

## 1. 显示样式

| Style | 几何 | 备注 |
|-------|------|------|
| `NONE` | — | 完全不绘制；时值缩放仍按音符 `Duration` 应用 |
| `NUMBER_ONLY` | 居中数字 | 适用于已被 beam 视觉合组的情况 |
| `BRACKET_AND_NUMBER` | 折线方括号 + 中央数字 | 两端短钩朝向五线谱（与 bracket 朝向相反） |
| `SLUR_AND_NUMBER` | 弧线（复用 `SlurCurveBuilder.buildLensPath`）+ 顶部数字 | 与 tie / slur 共用几何 |

## 2. 朝向选择

`TupletLayoutComputer` 取连音组**起始事件**的 `StemDirection`：

- `UP` → bracket / slur 在五线谱**上**方（`SlurDirection.ABOVE`）
- `DOWN` → 在**下**方
- 起始为休止符时默认 `ABOVE`

水平方向 X 取成员事件的符干 / 符头锚点；垂直方向按每个成员的外侧范围（符干端、符头边缘，休止符用自身中心附近）斜放 baseline，并向外平移直到中间成员不穿线。成员查询必须按真正的 `voiceTrackId` 隔离声部，不能用 `VoiceEventLayout.trackId`（该字段是谱表轨 ID），否则同谱表的其他声部会错误参与端点和避让计算。分页 / 分行模式下，同一 `staffIndex` 会在多个 system 中复用，连音成员的 `StaffLayoutInfo` 必须通过 `LayoutQuery.staffLayoutFor(event)` 按事件所在 `systemIndex` 解析，不能直接用扁平 `staffLayoutByIndex`，否则后续系统的连音会拿错 Y 基线。

## 3. 跨小节 / 跨系统

按项目决议**不做特殊处理**：渲染器在解析出的首尾端点之间直接绘制。跨小节、跨系统的视觉折断由后续布局拆分实现，本模块保持简单。

## 4. 交互

整个 tuplet（无论几条线 / 一段弧）注册一个 [`VoiceTupletSection`](../data_model/storage.md)，键为起始事件，方便点选编辑。
