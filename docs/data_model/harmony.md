# 和声求解数据模型

> 状态：第一阶段运行时模型已实现；序列化 spec 与独立节奏仍为 🚧。
>
> 本文只定义乐理求解层的不可变数据。乐谱持久化仍遵守
> `Storage → Runtime → Computed → Render Geometry`；求解器的候选状态不直接进入 Storage。

## 1. 正交概念

自由和声求解不得再用一个 `Key` 同时表示调号、局部主音、调式与和弦词汇。

| 概念 | 职责 | 不负责 |
|---|---|---|
| `NotationalKeySignature` | 谱面默认升降号与调号拼写 | 决定可用和弦、和声功能 |
| `TonalContext` | 局部主音、音阶/调式、音级解释 | 固定某种教材规则 |
| `ChordDefinition` | 和弦成员及结构兼容性质 | 名称、功能、调式或章节 metadata |
| `ChordVocabulary` | 为调式/风格选择可用和弦定义 | 四部排布与声部进行 |
| `ChordTarget` | 某槽的具体和弦、低音与调性解释 | 全曲唯一调性 |

同一归集条目可同时拥有多个 `ChordInterpretation`，但一个活动搜索目标只能选择其中一个。
共同和弦转调因此表示为“同一发声音集合，搜索状态在不同位置选择不同功能解释”，而不是复制
一个特殊和弦类型，也不是把多个解释的规则同时施加到一个目标。

副属和弦同样不增加封闭 `ChordQuality`：`SecondaryHarmonyType` 保存目标音级、变化根音、
功能族与调式来源，构造后作为 `ChordInterpretation` 进入统一目录。章节符号协议与自由求解器
消费同一目录，避免把 `V/x` 退化成仅供显示的标签。

省略根音的属九减七解释当前使用兼容类型 `RootlessDominantNinthType`：`soundingRootDegree /
soundingRootAlteration` 描述实际发声减七和弦的拼写根音，`omittedRootDegree /
omittedRootAlteration` 描述下降半音得到的实际属根音，`tonicizedDegree` 描述它所指向的
调内目标。统一模型把相同拼写音集合收进一个 `ChordCatalogEntry`，每种“下降哪一个音”的用法
保留独立 `ChordInterpretation`；功能 metadata 不再写入物理 `ChordDefinition`。

关系规则需要只命中特定解释时应选择 `InterpretationId`；结构规则选择 `SonorityId` 或实际成员。
`TargetSelector.identityKeys` 仅作为迁移期兼容入口，不能继续承担所有身份语义。

完整的构造、归集与单解释搜索模型见
[chord-construction-and-interpretation.md](chord-construction-and-interpretation.md)。

已实现的转调查询层用 `ModulationChordId(root pitch class + quality)` 表示可听身份，
`ModulationChordInterpretation` 保存每个调各自的级数、拼写构成音与相对构成音。练习中的
共同和弦与两侧调性词汇一同收集；其选中解释通过 `compatibleContextIds` 同时属于源、目标上下文；
查询 DTO 不进入 Storage，也不复制求解器目标类型。

调号变化和局部调性变化彼此独立：短暂离调可不改调号，较长转调可在合适的小节边界修改调号。

## 2. 调性计划

```kotlin
data class TonalContext(
    val id: TonalContextId,
    val tonic: SpelledPitchClass,
    val scale: ScaleDefinition,
    val keySignature: NotationalKeySignature? = null,
)

data class TonalSpan(
    val window: SlotWindow,
    val context: TonalContext,
)

data class TonalPlan(
    val spans: List<TonalSpan>,
)
```

- 每个求解槽必须至少有一个有效调性解释。
- 转调候选可在相邻槽切换 `contextId`。
- 共同和弦槽可携带源、目标两个解释，切换成本由转调 policy 评分。
- 音级、倾向音与习惯进行一律相对当前 `TonalContext` 求值。

`ScaleDefinition` 是音级拼写与半音结构的数据，不应继续由封闭 `Mode` 枚举垄断；
现有 `Mode` 作为内置音阶目录的兼容入口。

## 3. 开放和弦定义

封闭的 `ChordQuality` 可保留为常用符号兼容层，但求解器的本体使用开放成员集合：

```kotlin
data class ChordMember(
    val id: ChordMemberId,
    val diatonicNumber: Int,       // 1, 3, 5, 7, 9, 11, 13...
    val semitones: Int,
    val role: ChordMemberRole,
    val omissionPriority: Int = 0,
)

data class ChordDefinition(
    val id: ChordDefinitionId,
    val members: List<ChordMember>,
)
```

成员身份不能只用 pitch class：`#11` 与 `b5` 即使等音，也有不同功能、拼写与省略规则。
`ChordDefinition` 至少区分：

- `STRUCTURAL`：根、三、七等决定和弦身份的成员；
- `AVAILABLE_TENSION`：爵士语境可作为稳定和弦成员的扩展音；
- `SUSPENSION`：替代三音等结构成员；
- `AVOID_TONE`：允许出现但默认高成本；
- `OPTIONAL_COLOR`：可自由省略的色彩音。

`ChordTarget` 引用 definition，并给出根音、低音、局部解释与转位。旧
`pitchClassFor(ROOT/THIRD/FIFTH/SEVENTH)` 在迁移期投影到通用成员查询。

## 4. 拼写音高

变化音和声必须从拼写成员生成 `Pitch`，禁止先枚举 `PitchClass` 再统一
`Pitch.fromMidi(preferSharps=true)`：

```text
TonalContext tonic spelling
  + scale degree / chord-member spelling
  + octave constrained by VoiceSpec.range
  = candidate Pitch
```

这样才能稳定区分：

- 增四度与减五度；
- C♯ 与 D♭ 的倾向；
- `b9 / #9 / #11`；
- 共同和弦在两个调性中的不同解释。

## 5. 任意固定声部

自由和声第一阶段支持任意 `N >= 1` 个同节奏、持续参与的单音声部：

```kotlin
data class VoiceSpec(
    val id: TrackId,
    val order: Int,                    // 从高到低的稳定身份次序
    val boundary: VoiceBoundary,
    val range: VoiceRange,
)

enum class VoiceBoundary {
    UPPER_OUTER,
    INNER,
    LOWER_OUTER,
}

data class PolyphonicVoicing(
    val slotIndex: Int,
    val target: ChordTarget,
    val pitchesByVoiceId: Map<TrackId, Pitch>,
)
```

`SOPRANO / ALTO / TENOR / BASS` 是四声部 preset 的标签，不再是通用规则选择器。
通用规则按 `OUTER / INNER / UPPER_OUTER / LOWER_OUTER / voiceId` 选择声部。

独立节奏、休止与声部进入/退出属于 `FREE_POLYPHONY` / figuration 阶段，不塞入第一版和声槽。

## 6. 和声槽时间与织体

`ConstraintProgram` 继续保留 `key` / `slotDomains` 兼容投影，同时新增：

- `tonalPlan`：逐槽活动调性，可重叠；
- `slots: List<ConstraintSlot>`：稳定 `HarmonySlotId`、真实 onset/duration、domain 与源事件锚点；
- `meterPlan`：由拍号和实际时间推导强弱拍；
- `texturePlan`：逐槽声明 `ChordMember` 或和弦外 `Sustained(pitch)`；
- `omissionPolicy`：候选完整性硬边界。三和弦最多省略一个和弦音，且只能省五音；七和弦
  最多省略一个和弦音，且只能省三音或五音，根音与七音始终保留。完整和弦仍是搜索首选，
  合法省略只作为第一层放宽候选，不因声部数是否恰好等于和弦音数而绕过。

候选生成的实际声部音高仍参与音域、交叉、间距和平行检查；和弦外持续声部不参与和弦完整性
与重复计数。低音为外音时，目标转位的结构低音必须由其余和弦声部实际呈现。

自由练习把计划意图与谱面材料分开保存：

- `HarmonyWorkspaceState` 是稳定和声槽、和弦解释、调性布局、枢纽标记与惯用进行实例的真相；
- `RuntimeScore` 是音符、休止、连线与谱表的唯一真相；
- 可见和弦记号与调性区域由工作区单向投影为 Runtime/Storage 插件轨，不作为第二份可编辑计划；
- finding、pattern 完成度和自动写作窗口请求由工作区与 `RuntimeScore` 联合派生。

调性布局由若干可重叠的 `WorkspaceTonalLayout` 表示。每条线使用稳定 id、调号与调式、
绝对起点和可空终点；`end = null` 表示随时间轴继续延伸。初始调性的基线固定从零开始，
右端可收束为有限终点，但仍不可删除。和弦槽保存当前采用的布局 id；同一时刻覆盖该槽的
其他布局提供并列功能解释。
枢纽和弦只是槽上的展示标记，不改变求解或规则语义。

惯用进行保存为 `WorkspaceIdiomInstance`，记录来源章节/练习、具体变体、布局、覆盖的稳定槽 id
以及可调整参数。属于实例的和弦槽不能通过普通替换、移动、缩放或删除命令直接修改；必须通过
实例级命令整体替换或移除，保证“从左侧调整”的所有操作保持原子性。惯用进行目录由教材章节
注册表聚合章节枚举/编译结果，自由练习不维护第二份和弦序列。移除实例只解除成员关系与锁定，
不会删除对应的和弦槽或 `RuntimeScore` 音符材料。

惯用进行目录变体另携带运行时目标调 `suggestedKey` 与相对初始调的最短五度圈距离
`targetKeyDistance`；它们不进入 workspace schema。“全部教材进行”始终只读取当前调默认词汇；
打开“展示离调”后，仅当前和弦的相关目录按大/小调式各枚举一次关系，再把音响、低音与精确解释
投影到完整目标调号集合，不按 30 个调号重复运行章节枚举，也不投影副属/副导进行。同一可听进行
按音响、低音与时值合并，只保留调号偏离最少的目标解释。等音匹配只用于目录 focus；Ger+6 的
属七重解释始终启用，自动复用目标调主属七惯用进行，其他等音离调关系受开关控制。凡“视作”关系
中的每一步音响都能在当前调目录取得精确解释时，目录会把整条进行重标为当前调的变化音和弦（目标
也允许是拿坡里等变化音和弦），写入当前调 `ChordInterpretationRef`，并保留原调与原解释 id 供教学
规则投影使用；此时不要求另建目标调性线。只有无法完整重解释的原目标调变体，插入时才要求对应
调性线完整覆盖进行范围。

`HarmonyWorkspaceState` 不再保存 `WorkspaceNote`。旧序列化字段仅供兼容载入，载入后立即物化为
Runtime 音符。现有 v1 槽仍暂存目录 identity 字符串；调性布局 id 决定该 identity 的解释上下文，
后续统一迁移为 `ChordInterpretationRef`，显示符号始终由 formatter 派生。

自由练习创建时同时固定 `VoicePlan` 与记谱映射 `VoiceNotationPlan`。前者供求解规则使用，
后者为每个稳定 voice id 分配独立 staff，并明确谱号与符干方向。五线谱点击哪个谱表就编辑
哪个声部，不再在同一谱表内维护 active voice。

钢琴卷轴允许用户指定声部，也允许选择“全部”让系统自动配声。工作区用事件 id 保存来源：

```kotlin
enum class VoiceAssignmentSource { AUTOMATIC, MANUAL }

data class HarmonyWorkspaceState(
    // ...
    val voiceAssignmentSources: Map<EventId, VoiceAssignmentSource> = emptyMap(),
)
```

手动指定的事件是固定材料；自动事件可在后续输入后重新分配。自动分配按 onset 从前到后处理，
每个时刻先锁定仍在持续的声部和手动事件，再对自动事件做保持音高次序的最小费用匹配：
禁止声部交错，优先落在声部音域内，并最小化相对各声部上一音的总半音距离。事件删除或换声部
后必须同步清理或迁移该映射。

工作区通过 `EditorStateController` 与 `HarmonyPracticeTransaction` 跟随同一个 undo/redo
历史项。事务提交必须原子发布 `RuntimeScore + ComputedScore + HarmonyWorkspaceState`，
观察者不得见到新谱面配旧计划或新计划配旧谱面的中间帧。

工作区命令先产生 `HarmonyWorkspaceEditResult`。校验失败时结果保留输入
`HarmonyWorkspaceState`，只携带 `errorMessage`；控制器不更新状态、事务不创建历史项。
数据类构造期的 `require` 继续保护序列化不变量，文件载入边界负责捕获并展示这些错误。

## 7. 用户意图与优先级

当前优先级采用“结构硬限制 + 可覆盖软评分”：

1. 数据可行性与显式固定材料；
2. 用户 `Require`；
3. 用户 `Prefer` 与指定动机；
4. 教材/风格软规则；
5. 排布移动成本与结果多样性。

固定目标、允许词汇、`VoicePitchPin` 与用户 `Require` 直接缩域或硬剪枝；通用规则只发
SOFT finding。`RuleProfile` 的用户 override 最后合并，可关闭或重设权重。真正的多级
词典序评分仍为 🚧，因此大量软成本之间目前仍按数值加总。

## 8. 兼容迁移

- `Key`：保留为单一 `TonalContext` 的便捷构造。
- `Chord` / `ChordQuality`：保留展示、识别和旧 API；内置质量映射到 `ChordDefinition`。
- `ChordVoicing`：保留 SATB 兼容投影；新求解出口使用 `PolyphonicVoicing`。
- `ConstraintProgram.key`：作为默认上下文保留；多调程序以 `TonalPlan` 和目标主 lens 为准。
- `VoiceRangeProfile`：兼容四声部 preset，新程序直接携带 `VoicePlan`。
