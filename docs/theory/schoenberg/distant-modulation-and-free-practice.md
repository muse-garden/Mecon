# 相差三 / 四个升降号的转调与自由练习

> 状态：核心架构、章节、2/4 持续音与探索输出已实现；自由练习详见独立设计稿。
> 前置：[../modulation.md](../modulation.md) ·
> [../free-harmony-solver.md](../free-harmony-solver.md) ·
> [../figuration.md](../figuration.md) ·
> [root-chord-selection-rules.md](root-chord-selection-rules.md)。

## 1. 结论

现有能力可以把一条已知路径“降级”为可求解的槽位、调性窗口和约束，但不能把“转调路径”
作为可选择、可编辑、可追踪完成度的一等概念：

- `TonalPlan` / `TonalSpan` 已支持逐槽多调上下文和共同和弦重叠；
- `InterpretedChordTarget` 已区分同一发声音响在不同调中的功能解释；
- `Constraint` 可表达固定槽位、窗口内存在、And / Or / Not 和 finding；
- 但 `ConstraintProgram` 仍只有一个默认 `key`，不拥有路径、真实节拍、跨槽持续声部或稳定槽 ID；
- 通用 `ProgressionTemplate` 只支持固定 offset，勋伯格终止式又使用另一套
  `SchoenbergSymbolicSequencePolicy`，两者无法直接承担路径、顺序、完成度和自由编辑。

因此不把新章节直接写成更大的 `SchoenbergModulation.compile`。先增加高层
`HarmonicPlan`，由统一编译器降级为 `ConstraintProgram`；后者继续作为求解执行 IR，
不承担课程 UI 工作流本身。

## 2. 三层模型

```text
HarmonicPlan（用户意图）
  ├─ ResolvedTonalPath：经过哪些调、用何种机制过渡
  ├─ PatternPlan：必须/可选的关键进行及顺序
  ├─ HarmonicTimeline：2/4 拍、槽位 onset/duration
  └─ TexturePlan：持续音、踏板音、参与和弦配置的声部
          │ HarmonicPlanCompiler
          ▼
ConstraintProgram（可执行 IR）
  ├─ 每槽候选域与主调性 lens
  ├─ 可枚举的硬约束、软评分与 finding
  ├─ 节拍时间线和声部参与计划
  └─ 源事件/槽 ID 锚点
          │ target-only 枚举 → 固定符号进行 → 四部实现
          ▼
StorageScore / RuntimeScore（谱面与自由练习的音乐材料）
```

`HarmonicPlan` 可序列化、可编辑；`ConstraintProgram` 可丢弃并由当前计划重新编译。
自由练习不得把编译后的大量 constraint 当作文档真相。

## 3. 转调路径

### 3.1 路径模板与实例

路径模板保存相对关系，不写死 C 大调：

```kotlin
data class TonalPathTemplate(
    val id: TonalPathId,
    val sourceMode: KeySignatureMode,
    val steps: List<TonalRelation>,
    val transitions: List<TonalTransitionTemplate>,
    val expectedFifthsDelta: Int,
)

enum class TonalRelation {
    DOMINANT_MAJOR,
    SUBDOMINANT_MAJOR,
    RELATIVE_MINOR,
    PARALLEL_MAJOR,
    PARALLEL_MINOR,
}

data class ResolvedTonalPath(
    val nodes: List<TonalPathNode>,       // 已解析为 TonalContext
    val transitions: List<TonalTransition>,
)
```

上述模板保留为教材预设，但不再是自由练习的运行时本体。自由练习使用
`TonalRoutePlan + CommonChordPivotStep` 逐边保存用户选择；每条边由相邻两个调、共同和弦
身份和词汇 ID 构成。模板载入后得到普通可编辑路线，用户可继续添加多次自然音级共同和弦
转调。`expectedFifthsDelta` 只校验教材预设，不限制通用路线。

同名大小调转换作为共同和弦路线的关系分类，不另设编译分支。路线可选择
`OPEN_FRAGMENT`，此时允许停留在尚未确认的调性区域，不自动追加终止式。

`TonalTransition` 明确记录机制：共同和弦重解释、共同属和弦、应用属和弦、同名大小调转换。
同一响度身份和功能身份不得混为一个字符串标签。

### 3.2 本章路径目录

| 距离 | 标准路径 | 可选简化路径 |
|---|---|---|
| +3 | 原大调 → 关系小调 → 同名大调 | — |
| +4 | 原大调 → 属大调 → 关系小调 → 同名大调 | 原大调 → 关系小调 → `V/V` → 目标大调；原大调 → 目标同名小调和弦重解释 → 属和弦 → 目标大调 |
| -3 | 原大调 → 同名小调 | 将原调 V 重解释为目标小调 V |
| -4 | 原大调 → 下属大调 → 同名小调 | 将原调 I 重解释为目标小调 V |

C 大调的金标准分别为 `C–a–A`、`C–G–e–E`、`C–c`、`C–F–f`。
简化路径仍必须产生同一目标调，路径编译器校验实际 `fifths` 差值，不能只相信模板名称。

新教学阶段只使用一个 exercise id：
`schoenberg.modulation.distant-three-four`，挂 `GENERAL_BRANCH_RULE_ID`。
路线、调性强度和持续声部是同一练习内的选择，不拆大小调 exercise/rule/descriptor。

## 4. 统一“惯用进行”与“关键进行”

新增通用 `HarmonicPattern`，替代继续扩充 offset-only `ProgressionTemplate`，并逐步承接
`SchoenbergCadencePolicy` 的序列结构：

```kotlin
data class HarmonicPattern(
    val id: HarmonicPatternId,
    val steps: List<PatternStep>,
    val contextBinding: ContextBinding,
    val rhythm: PatternRhythm? = null,
    val textureRequirements: List<TextureRequirement> = emptyList(),
)

data class PatternRequirement(
    val id: PatternRequirementId,
    val patternId: HarmonicPatternId,
    val placement: PatternPlacement,       // 固定、区域内、区域末尾、某 transition 附近
    val occurrence: OccurrenceRequirement, // 必须一次、至少一次、可选
    val after: Set<PatternRequirementId> = emptySet(),
)
```

同一 pattern 编译出四种消费物：

1. 符号枚举器的前缀自动机：增量剪枝并计算完成所需最少剩余槽位；
2. runtime `Constraint`：固定进行与外部传入进行使用同一硬语义；
3. `PatternMatcher`：自由练习显示未开始 / 部分完成 / 已完成；
4. continuation 查询：把尚未完成的 pattern 作为后续搜索目标。

终止式、阻碍终止、`V–X–V` 持续音形、副属、减七和弦连接都进入同一目录。
根音方向、类似进行与评分 policy 仍是正交规则，不塞进 pattern 定义。

pattern 的目标选择必须绑定“主解释 lens”，不能只靠 `degree`：

- 普通槽要求目标在指定 `TonalContext` 下作为主解释；
- 共同和弦可声明兼容多个 context；
- 重解释 transition 显式比较 `sonorityIdentity` 相同而 `interpretationIdentity` 改变。

## 5. ConstraintProgram 的基础扩展

`ConstraintProgram` 增加以下执行信息，旧程序由兼容构造器生成单调、等时四分音符默认值：

```kotlin
data class ConstraintSlot(
    val id: HarmonySlotId,
    val time: HarmonicTimeSpan,
    val domain: SlotDomain,
    val sourceAnchor: EventId? = null,
)

data class ConstraintProgram(
    val defaultKey: Key,              // 迁移期兼容
    val tonalPlan: TonalPlan,
    val slots: List<ConstraintSlot>,
    val texturePlan: HarmonicTexturePlan = HarmonicTexturePlan.allChordVoices(),
    // 既有 constraints / ruleProfile / searchConfig...
)
```

`length` 与 `slotDomains` 可暂时保留为兼容投影。上下文敏感规则优先读取 target 的主 lens；
仅无 lens 的旧 target 回退 `defaultKey`。

现有 `SchoenbergModulation.projectChapterConstraint` 不继续复制到三 / 四段路径。
先抽出 `SchoenbergRegionalConstraintBundleFactory`，按 `(context, window, treatments)` 直接创建
区域规则包。旧共同和弦转调也迁移到同一编译器，作为架构回归卡尺。

## 6. 2/4 拍与和声时间线

`WritingTimeline` 不能再用 `TimeCode.of(1, slot, 4)` 伪造所有槽。新增真实的
`HarmonicTimeSpan(onset, duration)` 与 `MeterPlan(TimeSignature(2, 4))`，拍权由时间推导，
不存重复的 `strong=true`。

本章默认：

- 一律显示 2/4；
- 一般和弦一拍；
- `V–X–V` 使用 `V(四分) – X(四分) – V(二分)`；
- 持续音在第一小节强拍开始；最终 V 从第二小节强拍开始，持续音保持到该强拍结束；
- 持续音的后继音从第二小节弱拍开始，最终 V 在该弱拍内保持不变；
- 后续目标主和弦从下一小节强拍开始。

因此持续音的结束边界与后继音的弱拍 onset 是同一时刻。和弦槽与声部音符 onset 不再一一
对应：最终 V 是一个二分音符长度的和声槽，但持续声部在其内部包含“强拍持续音 + 弱拍后继音”
两个表面事件。这也是不能继续把 `ConstraintProgram` 的每槽单音直接当作最终谱面的原因。

调性与谱面调号继续正交：离调节点不必改调号；最终目标调被确认后，才在由路径实例给出的
小节边界写 `StorageKeySignatureChange`。

## 7. 持续音与踏板音

自由练习目录不再把完整远关系转调与属持续音合成一个惯用进行。转调入口改为多调性重叠区的
枢纽和弦推荐：1–2 个升降号差取公共自然音和弦，3–4 个升号差统一取前调 `3–♯5–7`，3–4 个
降号差统一取后调 `3–♯5–7`。本节既有确认窗口则复用为独立的
“属持续音（增强转调目标调性）”惯用进行，可按当前锚点插入。

### 7.1 术语与约束

高层使用 `SustainedToneRequirement`；只有当所选声部是最低外声部时，finding / UI 称
“踏板音”。现有 `NonChordToneType.PEDAL` 保留兼容，并新增普通声部持续音分类，避免把所有
持续音错误命名为踏板音。

```kotlin
data class SustainedToneRequirement(
    val window: SlotWindow,
    val voiceId: TrackId,
    val pitchSource: SustainedPitchSource, // 如目标调属音
    val endpointMembership: EndpointMembership = BOTH,
    val releaseMetric: MetricBoundary,
)
```

本章三 / 四个升号路径在同名大小调转换处要求：

- 持续音高为目标调属音；
- 首尾目标均为目标调 V（可配置 V7）；
- 中间目标为 IV、VI 或 ii；
- 同一实际 `Pitch` 在同一声部保持整个窗口；
- 首尾该音属于和弦，允许中间不属于和弦；
- 窗口之后再进入目标 I，并可继续阻碍终止、自由进行和最终终止式。

### 7.2 不能只扩展 VoicePitchPin

当前候选工厂先把每个声部限制在 `target.sonority.pitchClasses`，之后才检查 pin，因此中间
和弦外持续音没有候选。低音又被直接固定为 `target.bassPitchClass`，把“实际最低音”和
“决定和弦转位的最低和弦成员”混为一体。

新增逐槽声部参与角色：

```kotlin
sealed interface HarmonicVoiceParticipation {
    data object ChordMember : HarmonicVoiceParticipation
    data class Sustained(val pitch: Pitch) : HarmonicVoiceParticipation
}
```

- 持续音若属于当前和弦，可参与完整性与结构低音判定；
- 若不属于，则退出和弦隶属、重复与完整性计数；
- 踏板音为外音时，转位由其上方最低的 `ChordMember` 决定；
- 一般音域、声部交叉、平行五八和声部间距仍检查实际全部声部。

三和弦在可用和弦声部不少于三个时默认完整呈现根、三、五。七和弦在声部不足时必须
保留根与七音，三音或五音均可省略；省五可作为较低成本偏好，
省三仍是合法候选。省略策略由 `ChordOmissionPolicy` 统一决定，不能在持续音章节另写
七和弦枚举特判。

输出把起点到最终强拍结束物化为一个跨槽长音；跨小节时拆分并显式 tie。弱拍后继音是新的
表面事件。连续重复攻击不算持续音。

## 8. 调性强化

路径“到达目标调”和“目标调已建立”分开建模：

```kotlin
enum class TonalConfirmationLevel { LIGHT, ESTABLISHED }
```

- `LIGHT`：完成路径 transition，出现目标调特征变化音，并以目标 `V–I` 收束；
- `ESTABLISHED`：在 LIGHT 上叠加路径专属证据。升号路径要求属持续音形；降号路径要求
  目标调阻碍终止或额外关键进行，最后仍回到正格终止。

两档都必须实现并独立验收；练习请求和自由练习均允许用户选择，不用其中一档替代另一档。
章节可分别展示轻量路径与强化路径的候选，不能只实现强化档后把 LIGHT 当作内部中间状态。

自由练习另外提供 `OPEN_FRAGMENT` 作为结束意图，它不是第三种确认强度，而是明确选择
“暂不确认目标调”。持续音、终止式等确认方式由无环 `TonalTechniqueGraph` 声明适用条件与
前置关系。同主音 `MINOR → MAJOR` 边会产生持续音建议，但建议不会自动启用或变成硬规则。

确认策略只组合共享 pattern 与特征音 predicate，不复制一套枚举规则。副属和弦、减七和弦
在目标区域来自既有统一词汇；是否“必须使用”由可选 `PatternRequirement` 决定。

## 9. 搜索管线

```text
解析路径与 pattern 顺序
  → 分配 2/4 槽位与 context windows
  → 分区域枚举符号和弦（共享 pattern 自动机 + 序列 policy）
  → transition pattern 拼接与 target-only 预检
  → 固定目标序列
  → HarmonicTexturePlan 感知的四部实现
  → finding / pattern completion / 调性证据
```

不把所有调、开放和弦和四部排列放进同一个 DFS。稳定调区可继续使用现有禁忌相邻表；
持续音窗口改变了“可用和弦声部数”，不得误用四个和弦声部生成的旧表。v1 在该窗口直接由
求解器判定；若以后需要表，按独立 texture profile 数据化生成，不写死例外。

跨调 transition 不套用任一侧单调性的根音音级差；由 transition pattern 负责符号语义，
一般四部音高规则仍连续生效。

## 10. 自由练习边界

自由练习复用本设计的 `HarmonicPattern`、时间线、调性计划与三值检查，不另造一套和声语义。
编辑事务、可配置固定声部、钢琴卷轴、拼写和续写设计见
[free-practice.md](free-practice.md)。

## 11. 实施顺序

1. **A0**：统一 `HarmonicPattern`；`ConstraintProgram` 接入调性、时间与织体计划；迁移旧转调。
2. **A1**：节拍、持续音、结构低音、省略策略及长音 / tie 物化。
3. **A2**：路径目录、`LIGHT` / `ESTABLISHED`、统一 exercise descriptor 与 2/4 输出。
4. **A3/A4**：自由练习 MVP、钢琴卷轴与续写，按独立设计稿实施。

A0/A1 完成前不接新章节；自由练习在半成品三值检查可靠前不宣称完成。

## 12. 关键测试

- 路径差值恒为 `+3/+4/-3/-4`；所有路线共用一个可移调 exercise id；
- pattern 的 enumerate、runtime constraint 与 matcher 判定一致；旧转调迁移前后等价；
- 2/4 槽时值配平；持续音强拍开始，在最终强拍结束，后继音从弱拍开始；
- 持续音单次攻击，最终 V 弱拍内和声不变；外音踏板不改变结构转位；
- 七和弦保留根与七音，省三、省五均可解；两种调性强度各有正例、反例和非空枚举；
- 稳定区域使用正确调性规则，transition 不误套单调根音方向。
