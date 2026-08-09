# 动机库与相似度（Motive Library）🚧 设计

> 状态：**设计阶段，未实现**。总览见 [README.md](README.md)。
> 覆盖 [todos/polyphony.md](../../todos/polyphony.md) "动机"一节。
> 动机匹配在**单音声部线条**上进行，缩谱（[reduction.md](reduction.md)）是前置。

## 1. 动机类型

一段旋律、一段和声进行（Mahler 6 大转小）、甚至一个特殊和弦（特里斯坦和弦）都可以
是动机。复调/曲式分析首先关注**旋律动机**，但类型协议开放：

```kotlin
@Serializable
sealed interface StorageMotive {
    val id: MotiveId              // @JvmInline value class
    val name: String
    val derivedFrom: Derivation?  // null = 原生动机
}

@Serializable @SerialName("melodic")
data class StorageMelodicMotive(
    ..., val shape: MotiveShape,
) : StorageMotive

// 🚧 预留：@SerialName("harmonic")  和声进行动机（质量序列，如 MAJOR→MINOR 同根音）
// 🚧 预留：@SerialName("sonority")  特性和弦动机（音程结构集合）

@Serializable
data class Derivation(val parentId: MotiveId, val transform: MotiveTransform)

@Serializable
enum class MotiveTransform { INVERSION, RETROGRADE, RETROGRADE_INVERSION }
```

**移调后的动机与原动机完全相同**（不产生新动机——规范形本身移调不变，见 §2）；
逆行/倒影产生**衍生动机**：动机库中独立条目，经 `derivedFrom` 与原动机构成家族树，
UI 按家族分组展示。

动机库存于作品文件：`StorageScore.motives: List<StorageMotive>`。跨作品动机库
（教材式动机比较）🚧 远期，届时以导入/导出解决，不引入全局存储。

## 2. 规范形（MotiveShape）

从谱面选段抽取，存**相对表示**使其天然移调不变：

```kotlin
@Serializable
data class MotiveShape(
    val steps: List<MotiveStep>,        // 相邻音关系，长度 = 音数 - 1
    val rhythm: List<Fraction>?,        // 各音时值比例（首音归一）；null = 与节奏无关
)

@Serializable
data class MotiveStep(
    val diatonicSteps: Int,             // 有向音级步数（+2 = 上行三度）
    val chromaticSemitones: Int,        // 有向半音数（区分大三度/小三度）
)
```

- 双轨表示（音级步 + 半音数）同时支持"严格移调相等"（两者都相等）与"调内平移相等"
  （音级步相等、半音数差 ≤1，覆盖大小调互换、同调不同音级起句——命运交响曲
  第一句从 3 上、第二句从 2 上开始）。与 `SpelledInterval` 的拼写敏感设计同源，
  抽取时由实际拼写计算。
- `rhythm = null` 表示纯音高动机（Mahler 8 开头下四上七），匹配跳过节奏维度。
- 倒影 = `diatonicSteps / chromaticSemitones` 取反；逆行 = 序列反转且步进重算。
  衍生动机的 shape 由变换派生，不单独手编。

## 3. 相似度分层

匹配结果不是布尔值，而是**音高层级 × 节奏层级**的二元组，层级越低关系越近
（与教材"关系最近的是……其次……再次……"的表述一一对应）：

**音高层级**：

| 层级 | 判定 | 典型场景 |
|------|------|---------|
| P0 | 严格移调相等（含原位不移调） | 模进、声部模仿 |
| P1 | 音级步相等，各步半音差 ≤1 | 大小调转换、不同音级起句 |
| P2 | 音级步相等，允许 ≤k 个音偏离超过 P1（k 可配） | 局部变化音装饰 |
| P3 | 大变换：轮廓（方向序列）相等即可，幅度不限 | 动机的较大变形，需用户确认 |

**节奏层级**（`rhythm != null` 时）：

| 层级 | 判定 |
|------|------|
| R0 | 时值比例逐一相等 |
| R1 | 等比例放缩（增值/减值） |
| R2 | 时值长短的**顺序**保持（如"短短短长"的 ordinal 模式不变） |
| R3 | 节奏完全打乱（只按音高匹配） |

**变换维度**：先按原形匹配，再对 INVERSION / RETROGRADE / RETROGRADE_INVERSION
的派生 shape 各匹配一轮；命中衍生形时若库中尚无对应衍生动机，提示用户"创建衍生动机"
（衍生关系显式入库，而非匿名标签）。

综合得分 = 层级组合的加权序（P0R0 最优），供检测结果排序与阈值过滤；默认只自动
呈现 ≤(P2, R2)，P3 命中列入"低置信建议"待确认——与求解器 finding 的
HARD/SOFT/HINT 分级哲学一致：不把模糊判定做成布尔断言。

## 4. 检测与匹配（MotiveMatcher）

```kotlin
object MotiveMatcher {   // :theory
    fun findOccurrences(
        line: List<MotiveNote>,          // 单音声部线（pitch + onset + duration）
        shape: MotiveShape,
        config: MatchConfig,             // 层级阈值、k、变换开关
    ): List<MotiveMatch>                 // 区间 + (pitchTier, rhythmTier, transform) + 得分
}
```

- 输入是抽象线条（`pitchOf` 回调风格，同 `MelodyAnalysis`），不绑定具体谱类型：
  缩谱声部、`FixedVoiceScore` 声部、总谱单声部旋律皆可喂入。
- 滑窗 + 步进比对；音数固定为 shape 长度（省音/加音变形 🚧 v2，届时引入编辑距离）。
- 匹配在缩谱上进行的理由：总谱表面有装饰与八度重复干扰；缩谱线条干净且经
  link 可把结果投影回总谱。直接在总谱单声部段落上匹配也允许（如无缩谱时的快速检查）。

## 5. 出现标注（`mecon.motive` 插件轨）

```kotlin
@Serializable @SerialName("mecon.motive.occurrence")
data class StorageMotiveOccurrence(
    override val id: EventId,
    override val onset: TimeCode,
    val motiveId: MotiveId,
    val anchors: List<EventId>,          // 构成本次出现的音符事件
    val transform: MotiveTransform?,     // null = 原形
    val pitchTier: Int, val rhythmTier: Int,
    val confirmed: Boolean = false,      // 用户确认过的出现 vs 自动检测建议
) : StoragePluginEvent()
```

- 沿用 custom-track 三层配方：注释谱表显示动机名 + 变换标记（如 "α⁻¹" 倒影）；
  `NoteStyleProvider` 给 anchors 着动机家族色。
- 检测结果先以 `confirmed = false` 写入（灰显建议），用户逐个确认/驳回——
  与 [form.md](form.md) 辅助检测、ai/roadmap "建议 + 用户确认"同一模式。
- 锚在缩谱上的 occurrence 经 `StorageNoteLink` 投影到总谱渲染；polyphony todo
  "变换织体后重新分析、保留之前动机"由此自然成立：动机库与 occurrence 独立于
  任何一份缩谱，新缩谱上重跑检测即可。
- 编辑后 anchors 失效（事件删除）→ occurrence 标记 stale（同 link 的 `DANGLING`），
  手动重跑检测清理，不自动重算。

## 6. 对复调与曲式的接口

- **复调分析**：声部间模仿 = 同一动机在不同声部、不同时移的 occurrence 对；
  可动对位/卡农检测消费 occurrence 集合（figuration §9 `MotiveAt` 的语义基础）。
- **曲式分析**：乐句/乐段边界建议参考动机 occurrence 密度与新动机引入点；
  主题-发展关系由动机家族树 + occurrence 分布呈现（[form.md](form.md) §5）。
- **写作方向**：动机可作为约束程序素材（"用动机 α 的倒影写答题"🚧 远期，
  挂 constraint-program `MotiveAt` spec）。`MotiveAt` 实现为逐音推进的模式自动机规则
  （constraint-program §3.1 配方），任何逐音产出声部线条的候选空间都可挂——包括
  figuration Stage 2 的装饰搜索（机会式：动机命中 INDICATION 加分；要求式：requirement +
  figuration §7.1 反向投影骨架锚点）与复调空间，见 figuration §9。

## 7. 开放问题

- 省音/加音变形的匹配（编辑距离阈值与性能）；
- 和声进行动机、特性和弦动机的最小可用版本时机（协议已预留）；
- 动机自动发现（无种子动机的重复片段挖掘）是否值得做，还是始终由用户圈定种子；
- P3（轮廓相等）的误报率控制与 UI 呈现（低置信建议的分页/折叠）。
