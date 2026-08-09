# 连音线与连奏线 (Ties & Slurs)

> 模块路径：
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/geometry/SlurCurveBuilder.kt`
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/layout/TieLayout.kt`
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/layout/SlurLayout.kt`
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/render/TieLayoutComputer.kt`
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/render/SlurLayoutComputer.kt`
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/elements/TieElement.kt`
> - `renderer/src/commonMain/kotlin/com/mecon/renderer/elements/SlurElement.kt`
>
> **状态**：✅ 连音线（含 let-ring）与 ✅ 连奏线（含嵌套与碰撞避让）均已实现，共享 `SlurCurveBuilder`

## 1. 数据来源

Storage 层的 `TieInfo` 只记录音乐语义；`ScoreGeometry.ties` 另以源事件 ID +
和弦音高下标记录排版几何：

```
TieInfo(
    pitchIndex : Int      // 和弦内音高下标（0-based）
    isLetRing  : Boolean  // true = laissez vibrer；false = 运行时启发式解析
)
```

Runtime 层在 `fromStorage()` 中将 `isLetRing = false` 的连音按启发式算法（见第 8 节）解析为 `RuntimeTieInfo.targetEvent`，再流转到 Computed 层：

```
ComputedTieTarget(
    targetEventId    : EventId?      // 普通连音的目标事件；let-ring / 未解析 为 null
    targetPitchIndex : Int?          // 目标和弦内的音高下标
    isLetRing        : Boolean
)
```

Renderer 不判断"是否生成连音线"——只读取上述字段并绘制。

同一系统内 tie 的自动结果会捕获为 `TieGeometry`。它与 `SlurGeometry` 一样以音头中心为
稳定锚点，并记录端点、方向、弧高与长弧塑形参数。缺少条目时自动排版；缓存失效时仅重算
受影响曲线。用户方向 / 曲率覆盖分别由 `directionLocked` / `manuallyAdjusted` 保持。

Tie 候选不通过 `computedEvents.values` 全谱扫描发现：`ComputedEventStore.tieSourceEventIds`
在事件 `put/remove` 时以持久集合增量维护；局部编辑则直接使用 `eventsInMeasureRange` 的
B+ tree 小节窗口。方向切换和曲率拖动属于纯几何编辑，复用现有 `ComputedScore`，只向
源—目标覆盖的小节发送 `ComputeChangeSet.forRange`，不会触发全谱 compute 或 layout。
拖动过程中不会逐帧提交 `ScoreGeometry`：画布隐藏已选曲线，并从当前显示帧的端点即时
重建临时贝塞尔路径；松手后才提交一次几何。跨系统曲线按各 stub 分段预览，取消拖动则
直接丢弃中间状态。曲线元素通过 `RenderResult.elementIndex` O(1) 读取；该持久索引在
增量渲染时仅删除、加入替换窗口内的元素，不在拖动帧遍历全谱元素。

## 2. 渲染管线

```
ComputedScore  ─┐
UnifiedLayout  ─┼─▶ TieLayoutComputer ─▶ List<TieLayout>
StaffLayouts   ─┘                              │
                                               ▼
                                          TieElement.render()
                                               │
                                               ▼
                                  SlurCurveBuilder.buildLensPath()
                                               │
                                               ▼
                                          DrawPath (filled)
```

`TieLayoutComputer` 在 `RenderEngine.renderUnified()` 第 5b 步运行——主布局与符杠都完成之后，注释谱表之前——以便能从 `NoteElement.noteBody.noteheads[i].geometry.bounds` 读到每个音头的最终位置。

## 3. 端点解析

每个连音的源/目标都需要解析为一个 `EventEnvironment`：

| 字段 | 来源 |
|------|------|
| `slotX` | `UnifiedLayoutResult.timeSlotMap.atTime(time).x` |
| `noteElement.relativeX` | `voiceEventLayouts[eventId]` → 找到 `NoteElement` |
| `staffLayout.centerY` | `staffLayoutByIndex[voiceLayout.staffIndex]` |
| `notehead.bounds` | `noteElement.noteBody.noteheads.find { pitchIndex == i }.geometry.bounds` |

绝对相对坐标：

```
startX = slotX + noteElement.relativeX + sourceNotehead.rightEdge + tieHorizontalInset
startY = staffLayout.centerY + sourceNotehead.centerY + tieVerticalGap * sign
```

`sign = +1` 当方向为 BELOW（屏幕下方），`-1` 当 ABOVE。`tieHorizontalInset` 与 `tieVerticalGap` 由 `RenderLayoutConfig` 暴露，默认 0.15 / 0.45 staff space。

## 4. 方向规则

`staffPosition` 约定：**数值越大 = 音符越高**（正数在中线上方，负数在中线下方）。

### 单音连音

| 符杆方向 | 连音线方向 |
|----------|----------|
| UP | BELOW（与符杆反向） |
| DOWN | ABOVE（与符杆反向） |
| 无符杆（全音符等） | `staffPosition > 0` → ABOVE，否则 → BELOW |

### 和弦连音（多个 pitch 同时带 tieTarget）

| 符杆方向 | 规则 |
|----------|------|
| UP | 最高音（`maxOf staffPosition`）→ ABOVE；其余全部 → BELOW |
| DOWN | 最低音（`minOf staffPosition`）→ BELOW；其余全部 → ABOVE |
| 无符杆 | 最高音 ABOVE、最低音 BELOW；内声部按偏向哪侧中心决定 |

只有一个 pitch 带 tieTarget 时退化为单音规则。

自动方向确定后，tie/slur 会在同 system、同 staff、水平跨度内分别试算默认方向和相反方向。
若换向后默认弧度即可避让，则优先换向；只有两侧均无法以默认弧度通过时，才选择所需增量
较小的一侧并提高最小 apex。用户或 MusicXML 通过 `directionLocked` 锁定的方向不会被翻转。
两侧试算复用 `LayoutQuery` 的 staff-local X 范围索引，不扫描全谱事件。

## 5. Let-Ring

`tieTarget.isLetRing == true` 时没有目标音头。`TieLayoutComputer.computeLetRingEndpoints()` 把终点合成到源音头右侧 `LET_RING_LENGTH = StaffSpace(1.6f)` 处，Y 与源端点持平，让曲线水平延伸后自然渐细。

`TieElement` 在 `RenderElement` 上写入 `metadata("letRing", "true")` 以供下游识别。

**跨谱表降级**：连音线只能连接同一谱表的两音。当源与目标的 `RenderingProps.crossStaffOffset` 不同（渲染在不同谱表）时，`TieTargetComputer` 直接产出 `isLetRing = true` 的目标，按上述 let-ring 方式绘制。连奏线（slur）则无此限制：`SlurLayoutComputer` 按各端点自身的 `staffIndex` 解析锚点，因此跨谱表的连奏线天然连接两谱表上的音符。

## 6. 几何：SlurCurveBuilder

镜片形（lens）路径 = 两条共享端点的三次贝塞尔。`buildLensPath()` 步骤：

1. `dx, dy` → 计算水平跨度 `span = abs(dx)` 和端点向量长度 `L = sqrt(dx² + dy²)`。
2. 垂直单位向量 `(nx, ny) = (sign * dy / L, -sign * dx / L)`——同时对斜向 slur 也正确。
3. 顶点高度 `apex = clamp(baseCurvature * sqrt(span), [minHeight, maxHeight])`，默认 `0.5 * sqrt(span)`，范围 0.6 ~ 4 staff space；若调用方传入的 `minHeight` 超过 `maxHeight`，`maxHeight` 仍作为硬上限生效。
4. 顶点张开 `halfGap = midpointThickness / (2 * APEX_FACTOR)`，`APEX_FACTOR = 0.75` 来自带平行控制点偏移的三次贝塞尔在 t=0.5 处取值的解析解。
5. 外/内曲线的控制点位于 `t = 1/3, 2/3`，沿 `(nx, ny)` 法向分别偏移 `outerOffset = apex + halfGap` 与 `innerOffset = max(apex - halfGap, 0)`。
6. Slur 可传入 `slopeDamping`：长跨度或端点 y 差较大的 slur 会减弱法向对端点斜率的继承，让中段更接近平直，两端保留弯曲。
7. Slur 可传入 `middleStraightening`：长跨度时外/内曲线按连续偏移函数拆成多段 cubic，不设置 shoulder 点。起止端继续沿用原单段 cubic 的切线方向，中部曲率逐渐降低，并保留很浅的 apex，避免宽跨度弧线变成完全平直的平台。
8. 默认路径 = `MoveTo(start) → CubicTo(outer cps, end) → CubicTo(inner cps reversed, start) → Close`；长 slur 拉直路径则包含更多 `CubicTo`，仍可直接交给 `DrawPath(fillColor=BLACK, strokeColor=null)`。

`lensBounds()` 用同一参数返回保守 AABB（取外侧顶点 + 两端点的极值），用于命中与脏区。

`midpointThickness` 来自 `EngravingDefaults.tieMidpointThickness`（Bravura 默认 0.22 staff space）。

## 7. Slur 实现

Slur 与 tie 几何完全相同（同一个 `SlurCurveBuilder`），区别在数据模型、配对算法、端点解析与方向策略。

### 7.1 数据模型 — 计数 + LIFO 栈

`StorageVoiceEvent` 上的两个计数字段决定声部上每个事件的 slur 开/合：

```
slurStarts: Int = 0   // 在此事件处开启的 slur 数量
slurEnds:   Int = 0   // 在此事件处闭合的 slur 数量
```

设计要点：

> **已提升为一等事件**：slur 现以 `StorageSlurEvent(id, startEventId, endEventId)` 存于
> `StorageVoiceTrack.slurs`（稳定 `id` = 几何键 + 手动编辑句柄），计数为 legacy 兼容输入。
> 加载与 `slurId` 派生见 [../data_model/storage.md §4.3](../data_model/storage.md)。以下计数 / LIFO 描述
> 仍适用于 legacy 路径（`SlurResolver.computeFromCounts`）。

- 用**计数**而不是 ID，因为 slur 的配对天然是 LIFO（参考 LilyPond 的 `\(...\)` 语法）。
- 同一事件可同时关闭内层并开启新层（例：`A )( B` → `slurEnds: 1, slurStarts: 1`），这就是为什么是计数而不是布尔。
- 一致的负数校验在 `init` 中 `require(>= 0)`。
- 字段不流入 `RuntimeVoiceEvent` 之上的层（Computed 用 `ComputedSlur`），但 Runtime 层透传以保留可逆性。

### 7.2 配对算法 — `SlurResolver`（`core/.../Computers.kt`）

每条 VoiceTrack 单独跑一次（slur 不跨声部）：

1. 事件按 onset 升序遍历。
2. 维护栈 `ArrayDeque<OpenSlur(startEventId, depthAtOpen)>`。
3. 对每个事件：先处理 `slurEnds` 次 pop——每次弹出栈顶并发射 `ComputedSlur(startEventId, endEventId = currentId, nestingLevel = depthAtOpen)`；再处理 `slurStarts` 次 push——以 push 时的栈深作为 `nestingLevel`（最外层 = 0）。
4. 遍历结束后仍在栈中的开口视为未匹配，被静默丢弃（按需可以改为 warning）。

`ComputeEngine.computeSlurs()` 跳过完全没有 slur 标记的声部以避免无效扫描。

### 7.3 方向策略

| 场景 | 方向 |
|------|------|
| `voiceNumber == 1`（独唱或上声部） | ABOVE |
| `voiceNumber >= 2`（下声部 / 多声部辅助） | BELOW |

不同于 tie 的"按符杆反向"，slur 优先考虑**多声部互不重叠**：每个 voice 永远走自己惯用的一侧。

### 7.4 端点解析

不同于 tie 以单个音头边缘为基准，slur 把整个**和弦**视为锚点：

- 方向 = ABOVE：取 `staffPosition` **最大**的音头（最高音）；端点 = 音头 `topY - slurVerticalGap`。
- 方向 = BELOW：取 `staffPosition` **最小**的音头（最低音）；端点 = 音头 `bottomY + slurVerticalGap`。
- 水平方向：notehead-side 端点使用该音头的 `centerX`，即正上方 / 正下方；stem-side 端点使用实际 stem tip。

这样厚厚的和弦也能让曲线干净地"罩在"音头外侧。

### 7.5 端点塑形、嵌套与碰撞避让

`SlurLayoutComputer` 先按中间音 / 演奏法的轮廓轻微调整端点 Y，再生成 `SlurLayout`。端点调整只吸收靠近两端的一部分侵入，避免端点连线过斜时把整条 slur 的 apex 突然抬高。随后把以下两项叠加到 `minApexHeight` 字段，并同时写入 `maxApexHeight`：

```
finalApex = max(
    baseApex + effectiveNestingGap,                  // 7.5.1 嵌套抬高
    collisionRequiredApex                            // 7.5.2 中间障碍物避让
)
finalApex = min(finalApex, maxApexHeight)            // 长 slur 保持较扁平
```

随后 `SlurElement` 把 `minApexHeight` / `maxApexHeight` / `slopeDamping` / `middleStraightening` 传给 `SlurCurveBuilder.buildLensPath()`，渲染层无需重新计算。

Stem-side 端点使用符杠 pass 的 `stemAdjustments`，所以 beamed notes 会按实际符杠后的 stem tip 定位；若调整量异常大（通常来自跨系统 / 分页边界的保护场景），会回退到原始 stem tip，避免 slur 被拉到错误系统。

#### 7.5.1 嵌套抬高

最外层 `nestingLevel = 0` 用 base；每多一层最多向外抬 `slurNestedGap = 0.6 ss`。该增量按水平跨度渐入：很短的 slur 不叠加嵌套抬高，中长跨度逐步恢复完整间距。这样真正的多层长 slur 不会互相穿插，同时避免 MusicXML 中长期打开的外层 slur 让局部短 slur 突然变成大弓。

#### 7.5.2 中间障碍物避让

对每个落在 `[startX, endX]` 区间且同 staffIndex 的障碍物，计算它相对于"start→end 直线基线"在 bow 方向上的 signed excursion，取最大值 + `slurCollisionMargin = 0.5 ss`：

```
required = max over obstacles in span of (
    bowDirection * (lineY(obstacleX) - obstacleOuterY) + margin
)
```

`bowDirection = -1` for ABOVE（屏幕向上为正）, `+1` for BELOW。障碍物包括：

- 音头朝 bow 方向那一侧的边（ABOVE 用 topY，BELOW 用 bottomY）。
- 中间事件的 articulation bounds。
- 中间事件的 stem tip；beamed notes 使用符杠 pass 写出的 `stemAdjustments`。
- 未成组符尾的近似外侧点。
- 已成组 beam 的外侧边：`BeamGroupProcessor` 先生成 beam group render data，`SlurLayoutComputer` 再按同一 beam thickness / spacing 对每个 beam 段采样。

碰撞只影响有限高度：靠近两端的侵入优先由端点塑形吸收，中部侵入再抬高 apex。无突出音符时，slur 的常规 apex cap 不超过 `2.0 ss`；只有 `collisionRequiredApex` 真正需要更高避让时才放开上限。这样宽跨度 slur 会保持浅弧，不会因为嵌套或局部音高突变占据过大的纵向空间。算法通过 `LayoutQuery.voiceLayoutsOnStaffInXRange()` 做 staff-local X 窗口查询，复杂度约为 O(log N + K)。

跨行 slur 会拆成 start-line / end-line 两段 stub。两段分别携带自身的 `systemIndex` 与端点所在小节：start-line 归起点系统，end-line 归终点系统；分页 streaming / splice 必须据此过滤和拼接，不能把两段都归到起点系统。两段也分别用自身所在 system 的端点事件作为查询锚点执行同样的中间音避让：start-line 扫描起点所在行，end-line 扫描终点所在行，避免下一行 stub 漏掉本行音符。

### 7.6 渲染流程位置

`RenderEngine.renderUnified()` 第 5b' 步：在 tie（5b）之后、tuplet（5c）之前生成 `SlurElement`。复用同一个 `unifiedLayout.voiceEventLayouts` + `timeSlotMap` 查询基础设施，故所有坐标已是最终位置。

### 7.7 交互注册

每条 `SlurElement` 注册 `VoiceSlurSection(startEvent, endEvent, nestingLevel)`，`sectionId = "slur:<startId>-><endId>:<nestingLevel>"`。嵌套层级编入 ID，所以重叠的多层 slur 可以独立选中。`RenderedScoreView.selectByPriority()` 给 slur 优先级 6（高于小节线 / 谱号 / 调号 / 拍号）。

### 7.8 持久化几何（overlay）

slur 几何可持久化到 `StorageScore.geometry`（[../data_model/storage.md §1.3](../data_model/storage.md)）：

- `SlurLayoutComputer.computeSlurLayouts` 接受可选 `geometry: ScoreGeometry?`。某 slur 在 overlay 中有条目
  （键 = `ComputedSlur.slurId`）→ `GeometryProjector.resolveSlur` 用**当前音头锚点 + 存储偏移 / 形状**重建
  `SlurLayout`（端点跟随音符）；无条目或无法解析（端点缺失 / 现已跨行）→ 回退现有自动 `buildLayout`。
- `GeometryProjector.toStored` 把已折叠 slotX/centerY 的 `SlurLayout` 折成锚点相对的 `SlurGeometry`
  （端点相对锚音头 + apex/damping 形状）。捕获(`toStored`)→重解析(`resolveSlur`)是亚像素恒等，故 overlay
  是自动结果的忠实缓存（`RenderGeometryOverlayTest`）。`RenderEngine.captureGeometry()` 按需从上一帧布局产出
  整谱 overlay，供保存写回（桌面端在每次渲染后经回调存入 `ScoreSession`，保存时折入 `StorageScore`）。
- 跨行 slur（多 stub）第一版不持久化。
- **增量失效（Phase 2，已落地）**：编辑乐谱时，一条 slur 的 overlay 条目 **stale（需 reshape，剔除→自动重排）**
  ⟺ 其端点事件被触动，或它的小节跨度与 `ComputeChangeSet.affectedMeasures` 相交（中间避让障碍移动 / 小节宽度
  变化）；跨度之外的编辑只让它整体平移，存储形状仍成立 → **reusable（按引用复用）**。判定与生效见
  [incremental-rendering.md](incremental-rendering.md)。

## 8. 连音目标解析算法

`TieInfo.isLetRing = false` 时，Runtime 层在 `RuntimeScore.fromStorage()` 第二遍遍历中按如下顺序搜索目标事件（实现：`RuntimeScore.kt` 末尾 `resolveTieTarget()`）：

1. **同轨道下一个 VoiceEvent**：若其包含相同 MIDI 音高，则连到该事件。
2. **跨小节回退**：若步骤 1 未匹配，且下一个事件**不在同一小节**（或不存在），则在下一小节开头（`beat.numerator == 0`）的第一个含相同音高的 VoiceEvent 处连线。
3. **降级为 let-ring**：上述条件均不满足（无后继同音高音符）时，`TieTargetComputer` 不丢弃这条显式连音，而是产出 `isLetRing = true`（`targetEventId = null`），按 let-ring（laissez vibrer）渲染。若之后在其后插入了同音高音符，步骤 1/2 会重新解析到该音符，弧线自动变回普通连接连音。
4. **✅ 装饰音规则**：若源音为装饰音（`onset.grace != null`），则从该装饰音起在**同一 `(measure, beat)`** 内向后扫描（即同组余下的装饰音 + 紧随其后的本音），返回第一个同音高的事件；扫到 `grace == null` 的本音后即停止。实现见 `TieTargetComputer.resolveGraceTieTarget`。

> ⚠️ 跨小节连音的约束：被连音符必须是所在小节**最后**一个 VoiceEvent，否则其后同小节事件会被步骤 1 优先匹配。设计乐谱数据时须注意此约束。

`TieTargetComputer`（Computed 层）在事件没有显式 `ties` 时仍使用原有启发式回退：寻找时间相邻且音高匹配的下一个事件。若目标事件已对该 pitch 声明显式 tie，则跳过启发式，避免叠加弧线（`candidateAlreadyTied` 检查，`core/.../Computers.kt`）。

### 路径锯齿

Compose for Desktop（Skia）在填充窄路径时，若不叠加描边则边缘会出现阶梯状锯齿。  
修复：`ComposeScoreRenderer.renderPath()` 在 `drawPath(style = Fill)` 之后，额外以相同颜色执行一次 `drawPath(style = Stroke(0.6f))`，利用 Skia 的描边抗锯齿平滑边界。

### Storage→Runtime 连音线解析

`RuntimeScore.fromStorage()` 采用两轮遍历：第一轮建立事件 ID 映射表，第二轮按启发式算法（见第 8 节头部）解析连音目标，以 `copy(ties = ...)` 补填 `RuntimeTieInfo`。

## 9. 关键调参

| 常量 | 文件 | 默认 | 作用 |
|------|------|------|------|
| `TIE_HORIZONTAL_INSET` | `RenderConstants.kt` | 0.15 ss | tie 端点距音头水平边缘的内缩 |
| `TIE_VERTICAL_GAP` | 同上 | 0.45 ss | tie 端点距音头垂直中心的偏移 |
| `MINIMUM_TIE_HEIGHT` | 同上 | 0.5 ss | 顶点最小高度（经由 `SlurCurveBuilder.DEFAULT_MIN_HEIGHT`） |
| `tieMidpointThickness` | `EngravingDefaults` | 0.22 ss | tie 顶点张开厚度 |
| `slurMidpointThickness` | `EngravingDefaults` | 0.22 ss | slur 顶点张开厚度 |
| `LET_RING_LENGTH` | `TieLayoutComputer.kt` | 1.6 ss | let-ring 渐隐曲线长度 |
| `SLUR_HORIZONTAL_INSET` | `RenderConstants.kt` | 0.25 ss | 保留的布局参数；当前 notehead-side slur 端点不使用水平内缩 |
| `SLUR_VERTICAL_GAP` | 同上 | 0.7 ss | slur 端点距外侧音头的垂直留白 |
| `DEFAULT_SLUR_NESTED_GAP` | 同上 | 0.6 ss | 每嵌套一层向外抬高的距离 |
| `SLUR_COLLISION_MARGIN` | 同上 | 0.5 ss | 中间音头避让的额外余量 |
| slur apex cap | `SlurLayoutComputer.kt` | ≤2.0 ss；碰撞可放开 | 无突出音符时限制顶点高度，避免长 slur 过高 |
| nesting gap ramp | `SlurLayoutComputer.kt` | 8→24 ss | 嵌套 slur 抬高随水平跨度渐入，短 slur 不吃完整 nesting gap |
| endpoint shaping cap | `SlurLayoutComputer.kt` | 1.25 ss | 中间音靠近两端时允许调整端点 Y 的最大幅度 |
| `slopeDamping` | `SlurLayout.kt` | 1.0→0.35 | 长 / 陡 slur 控制点对端点斜率的继承比例 |
| `middleStraightening` | `SlurLayout.kt` | 0→1 (18→48 ss) | 长 slur 中段曲率逐渐降低，中央保留浅弧，端部仍保持原 cubic 曲率 |
| `APEX_FACTOR` | `SlurCurveBuilder.kt` | 0.75 | 控制点偏移→顶点偏移的解析常数；不应改动 |
| 抗锯齿描边宽度 | `ComposeScoreRenderer.kt` | 0.6 px | hairline stroke 宽度，可微调（过大会使曲线视觉变粗） |
