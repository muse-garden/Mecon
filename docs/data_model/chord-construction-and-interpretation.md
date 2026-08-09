# 和弦构造、归集与功能解释

> 状态：🚧 迁移中；R4B 功能关系与首个构造 variant 已实施。
>
> 相关文档：[harmony.md](harmony.md) ·
> [../theory/constraint-architecture.md](../theory/constraint-architecture.md) ·
> [../theory/schoenberg/secondary-harmony.md](../theory/schoenberg/secondary-harmony.md) ·
> [../theory/schoenberg/diminished-seventh.md](../theory/schoenberg/diminished-seventh.md)

## 1. 三个不可混合的阶段

变化音和弦进入求解器前必须依次经过：

```text
ChordRecipe
    → ConstructedChord
    → ChordCatalogCollector
    → ChordCatalogEntry(sonority + interpretations)
    → InterpretedChordTarget(sonority + one interpretation + bass tone)
```

- **构造**回答“按照哪条教材推导得到哪些拼写音”。
- **归集**回答“哪些构造结果实际是同一个和弦音集合”。
- **解释**回答“当前搜索分支按哪种功能及规则处理该音集合”。

禁止用和弦质量、功能标签或 recipe id 代替实际组成音进行归集；也禁止把一个条目的多种解释
同时施加到同一搜索状态。

## 2. 和弦构造

构造 API 使用 Kotlin recipe，不在本阶段建立可序列化 DSL：

```kotlin
interface ChordRecipe {
    val id: ChordRecipeId

    fun construct(
        context: ChordConstructionContext,
    ): Sequence<ConstructedChord>
}

data class ConstructedChord(
    val tones: List<SpelledSonorityTone>,
    val structuralRoot: SpelledPitchClass?,
    val interpretation: ChordInterpretation,
    val trace: ConstructionTrace,
)
```

构造器提供少量正交原语：

- 从 `TonalContext` 取指定音级；
- 生成变化后的音阶/调式；
- 在某音级上叠置三度；
- 改变、加入或省略成员；
- 从已有 recipe 派生并保留 `ConstructionTrace`。

副属和弦由临时主音的调式构造；省略根音属九的减七和弦由 `V♭9` 省略根音构造。
章节不应再分别维护另一套音高推导。

## 3. 按实际组成音归集

```kotlin
data class ChordCatalogEntry(
    val sonority: ChordSonority,
    val interpretations: List<ChordInterpretation>,
)
```

默认归集键为 `SpelledToneSetKey`：把每个组成音的 `NoteName + chromaticOffset` 排序后形成稳定键。
它忽略构造顺序、功能和临时选择的根音，但保留等音异写。

另提供 `SoundingPitchClassKey` 用于查找可听上相同的集合，不作为默认合并键。否则拿坡里、
增六和弦与对称减七的记谱语义会被提前抹平。

身份分为：

| 身份 | 内容 | 典型用途 |
|------|------|---------|
| `SonorityId` | 拼写组成音集合 | 归集、排列候选缓存 |
| `InterpretationId` | 一个调性/功能解释 | 规则选择、进行模板 |
| `RealizationId` | sonority + bass tone | 转位、实际四部排列 |

exploration 的 `TargetSelectorSpec` 与 `SchoenbergChordFilterSpec` 同步保留
`requiredPitchClasses`、`sonorityIdentityKeys`、`interpretationIdentityKeys`。跨模块编译不得退化成
只比较音级、性质和转位，否则多重解释会在进入求解器前丢失。

## 4. 功能解释

功能 metadata 不属于 `ChordDefinition.names/tags`。同一实际和弦可能有互斥的规则解释，
因此功能信息由 `ChordInterpretation` 持有：

```kotlin
data class ChordInterpretation(
    val id: InterpretationId,
    val context: TonalLens,
    val symbol: FunctionalChordSymbol,
    val function: HarmonicFunction,
    val toneRoles: Map<FunctionalToneRole, SonorityToneId>,
    val treatmentIds: Set<HarmonicTreatmentId>,
    val tags: Set<InterpretationTag>,
    val trace: InterpretationTrace,
)
```

结构规则直接选择 `SonorityToneId`；功能规则通过语义角色选择实际成员，例如：

- `LOCAL_LEADING_TONE`
- `CHORDAL_SEVENTH`
- `ALTERED_TONE`
- `OMITTED_DOMINANT_ROOT_NEIGHBOR`

这允许同一 `2–♯4–6` 音集合在小调变化音解释下使用一套进行规范，在副属解释下把
`♯4` 作为局部导音处理。

## 5. 搜索语义

求解域中的目标必须只选择一个解释：

```kotlin
data class InterpretedChordTarget(
    val sonority: ChordSonority,
    val interpretation: ChordInterpretation,
    val bassToneId: SonorityToneId,
) : ChordTarget
```

开放槽位展开为：

```text
catalog entry × interpretation × allowed bass member
```

搜索 frame 保存实际选择的 `InterpretationId`。相同 sonority/bass 下的四部排列按
`RealizationId` 缓存，不因解释数增加而重复枚举音高。

不得把 `ChordCatalogEntry.interpretations` 的规则取并集后检查一次；这会让同一和弦同时承担
互相冲突的功能要求。

## 6. 重复与缓存身份

原先单一的 `identityKey()` 无法同时满足归集、功能重复和排列缓存。关系约束必须显式声明比较轴：

```kotlin
enum class ChordIdentityMode {
    SONORITY,
    FUNCTION,
    TARGET,
}
```

- `SONORITY`：同一组成音集合视为相同；
- `FUNCTION`：同一功能解释视为相同，转位不改变功能；
- `TARGET`：解释与低音成员均相同才相同。

`AllDifferent`、相似和弦距离、diversity key 和缓存分别选择所需模式，禁止再次依赖一个拼接字符串
隐式决定所有语义。

## 7. 参考、替代与详情投影

运行时已用 `HarmonicTreatment` 组合规则族，并显式区分“参考”与“替代”：

```kotlin
data class HarmonicTreatment(
    val id: HarmonicTreatmentId,
    val references: Set<HarmonicTreatmentId>,
    val substitutesFor: Set<HarmonicTreatmentId>,
    val ruleFamilies: Set<HarmonicRuleFamilyId>,
)
```

`HarmonicTreatmentRegistry.resolve` 展开引用闭包并返回 `substitutionTargets`。减七的
`ROOTLESS_DOMINANT_NINTH` 已明确替代 `DIATONIC_DOMINANT`；这条本体关系是详情显示“替代属功能”的
授权来源，UI 不解析 rule id、constraint 文案或常见后继来猜功能关系。

R4B 已在线路上补充可呈现的具体关系和构造本体：

```kotlin
sealed interface ChordFunctionRelation {
    data class SubstitutesFor(
        val targetTreatmentId: HarmonicTreatmentId,
        val function: HarmonicFunction,
        val tonicizedDegree: Int? = null,
    ) : ChordFunctionRelation
}

sealed interface ChordConstructionDetail {
    data class OmittedFromFormula(
        val basis: ChordConstructionBasisRef,
        val tones: List<ChordConstructionTone>,
    ) : ChordConstructionDetail

    data class AugmentedSixthDerivation(
        val kind: AugmentedSixthConstructionKind,
        val origin: AugmentedSixthConstructionOrigin,
        val augmentedSixthTones: List<ChordConstructionTone>,
        val descendingEndpoint: SpelledPitchClass,
        val ascendingEndpoint: SpelledPitchClass,
        val resolutionTone: ChordConstructionTone,
        val resultSymbol: String,
        val alterationDescriptionKey: String,
    ) : ChordConstructionDetail
}

sealed interface AugmentedSixthConstructionOrigin {
    data class RootlessAppliedChord(
        val basis: ChordConstructionBasisRef,
        val tones: List<ChordConstructionTone>,
        val rootlessResultNameKey: String,
    ) : AugmentedSixthConstructionOrigin

    data class NamedChord(
        val symbol: String,
        val tones: List<ChordConstructionTone>,
    ) : AugmentedSixthConstructionOrigin
}

data class ChordConstructionBasisDefinition(
    val id: ChordConstructionBasisId,
    val primaryNameKey: String,
    val secondaryNameKey: String,
    val romanNumeral: String,
)
data class ChordConstructionBasisRef(
    val definition: ChordConstructionBasisDefinition,
    val tonicizedDegree: Int,
)
enum class ConstructionToneRole { ROOT, THIRD, FIFTH, SEVENTH, NINTH, OTHER }
enum class ConstructionTonePresence { SOUNDING, OMITTED }
data class ChordConstructionTone(
    val degree: Int,
    val alteration: Int,
    val spelling: SpelledPitchClass,
    val role: ConstructionToneRole,
    val presence: ConstructionTonePresence,
)
```

- `ChordExplanationDefinition` 保留组内一致的 `ChordFunctionDetail`；`ConstructionRoute` 持有
  `functionRelations` 与一个 `construction`。原 `ConstructionOperation` 可继续作推导轨迹，但 UI 不再把
  operation 类型直接翻译成无参数句子。
- `SubstitutesFor.targetTreatmentId` 必须属于当前线路 `connectionRefs` 经 registry 解析出的
  `substitutionTargets`；`tonicizedDegree` 决定显示“属”还是“指向第 x 级的副属”，不改变 treatment 本体。
- `OmittedFromFormula.tones` 同时生成动态公式和谱例；首版要求恰有一个 `OMITTED` 根音，其余
  `SOUNDING` 拼写集合与线路 sonority 完全相等。音级来自当前 `TonalContext`，不是 i18n 字符串。
- 基础和弦先独立定义名称和罗马数字；减七 route 只引用属九定义并给出目标音级，由引用生成 `V9`、
  `V9/V` 等符号，禁止在减七 mapper 或文案中重写属九名称与符号。
- 拿坡里“替代属前”须使用专属 treatment；小下属 typed 构造显式保存借用调区及当前大调的共同功能参照。
- `AugmentedSixthDerivation` 同时驱动公式、展开后的构造文字与紧凑谱例。It/Ger 使用
  `RootlessAppliedChord` 保存完整副属七/九公式，其中虚拟根音标为 `OMITTED`；Fr 的 `NamedChord`
  是对应 ii7，`ø+6` 的来源与实际半减七音响相同但保留独立解释。增六事件保持 `♭6` 为最低、
  `♯4` 为最高的外声排列；It/Ger 第一事件只重排实际减三/减七成员以对应第二事件，虚拟根置于
  最下方并仅在该事件灰显。第三事件用同一目标音的两个八度表示两端分别到达上下目标音。
  来源与结果相同的前两事件只画一次。动态公式、变化步骤、两端拼写
  和解决音必须来自同一 typed DTO，UI 不从和弦标题反推。

## 8. 统一运行路径

- `DefinedChordTarget` 与旧 `TonalInterpretation` 已删除；活动搜索目标统一为 `InterpretedChordTarget`。
- 自然和弦、副属和弦、无根属九与调制和弦都先生成 `ConstructedChord`，合并到
  `ChordCatalog` 后才展开转位和解释。
- `SecondaryHarmonyVocabulary` 与 `RootlessDominantNinthVocabulary` 只保留类型推导、recipe 和
  catalog 门面，不再生成带 tags/names 的兼容 `ChordDefinition`。
- 共同和弦通过 interpretation 的 `compatibleContextIds` 声明可同时属于源调和目标调。
- exploration schema v5 使用 `WorkspaceChordChoice` 传递音响与可空锁定解释，不再保留按和弦族命名的
  专用请求字段。

## 9. 可听音响、解释组与选择身份 ✅

`SoundingClassId` 只在章节 family 内合并等音卡片，`AudibleSonorityKey` 只负责跨章节发现；二者
都不改变拼写敏感的 `SonorityId`、功能解释或求解规则。自由练习以
`ChordSelectionOriginRef + AudibleSonorityKey` 查询解释组，再以 `ConstructionRouteId` 选择或锁定
唯一 `ChordInterpretationRef`。完整身份表、发现流程与宿主边界集中维护在
[和弦详情与游移和弦](../theory/chord-detail-and-vagrant-chords.md)，此处不再重复。

## 10. 不变量

1. `ChordSonority` 的音列表不可变且不为空，`SonorityId` 由 `SpelledToneSetKey` 稳定导出。
2. 一个 `ChordCatalogEntry` 内 interpretation id 唯一，且全部解释同一个 sonority。
3. 一个 `InterpretedChordTarget` 恰好有一个 interpretation 和一个属于 sonority 的 bass tone。
4. 功能规则必须能从 interpretation 的 typed tone role 找到实际成员，禁止从显示名称反推。
5. 章节新增和弦只注册 recipe、treatment 与规则贡献，不修改中心联合类型。
6. `SoundingClassId` 只在家族内部聚合选择，`AudibleSonorityKey` 只建立发现索引；两者都不改变
   `ChordCatalogCollector` 的拼写敏感归集。
7. 一个解释组可以包含多条精确路线；同一 `ChordExplanationId` 的公共详情必须一致。
8. 未锁定音响可以持久化，但每个求解分支仍必须解析为唯一 `ChordInterpretationRef`。
9. UI 可见的替代关系必须通过 treatment registry 校验，不能由 constraint 文案或后继和弦反推。
10. 动态构造文字与结构谱例必须消费同一 `ChordConstructionTone` 列表，省略音不属于实际 sonority。
