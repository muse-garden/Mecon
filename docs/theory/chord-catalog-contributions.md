# 和弦目录贡献协议

> 状态：JVM 反射发现、通用 theory 目录、桌面选择组件、命名子集、家族内音响聚合，
> 以及跨章节音响解释发现与共享章节顺序均已实现。

## 1. 分层

和弦目录分为三层：

1. 乐理章节声明 `ChordCatalogContribution`，拥有类别、顺序、文字 key、构造来源与符号投影；
2. theory 的 `ChordSelectionCatalog` 反射发现章节并生成与 UI 无关的分组及候选；
3. `desktop-ui-kit` 的 `ChordCatalogPicker` 只渲染已本地化的通用 UI DTO。

自由练习、和弦分析及后续功能不得维护各自的和弦名称白名单或质量后缀表。

## 2. 新和弦类型接入

在拥有该和弦教学与构造语义的章节对象上：

1. 实现 `ChordCatalogChapterProvider`；
2. 添加运行时注解 `@DiscoverableChordCatalogChapter`；
3. 在 `chordCatalogContributions` 中增加一个或多个贡献；
4. 用唯一 `id` 和 `Int order` 确定稳定类别顺序；
5. 提供 `titleKey` / `descriptionKey`，并在中英文 i18n bundle 中注册；
6. `construct` 必须委托章节已经使用的共享 vocabulary，不在目录层重写音高或和弦；
7. 仅当存储符号和用户所需功能符号不同，才提供 `symbolProjection`；非罗马数字的章节专用标签
   （如 `It+6` / `Ger+6` / `Fr+6` / `ø+6`）由贡献的 `symbolLabelProjection` 生成，UI 不按 quality 猜测；
8. 教材规定卡片按功能目标而非结构根音排序时，由贡献的 `choiceOrderProjection` 返回稳定整数键；
   catalog 只先比较该键，再使用根音、符号与 id 作稳定兜底。增六章节据此按目标音级排序，并把
   指向 V 的清晰属前组作为 named subset 放在拿坡里之后、一般小下属之前。

示意：

```kotlin
@DiscoverableChordCatalogChapter
object ExampleChapter : ChordCatalogChapterProvider {
    override val chordCatalogContributions = listOf(
        ChordCatalogContribution(
            category = ChordCatalogCategoryDescriptor(
                id = "example-colors",
                order = 800,
                titleKey = "exploration.chordCatalog.example.title",
                descriptionKey = "exploration.chordCatalog.example.description",
            ),
            construct = { context, key ->
                ExampleVocabulary.constructedChords(context, key)
            },
        )
    )
}
```

普通和弦贡献不需要修改 `ChordSelectionCatalog`、自由练习工作台或 `ChordCatalogPicker`。
游移和弦若需要按可听音响去重，应声明 family-scoped selection projection；禁止自行改变
`ChordCatalogCollector` 的拼写敏感归集，也禁止在自由练习中后处理去重。
当前增三与减七贡献都走该投影：同一家族内相同 pitch-class 集合只形成一张选择卡，所有解释
引用与线路仍保留。每个分类统一按候选的规范显示根音 pitch class 升序排列，符号仅作同根音时的
稳定次序，不再按罗马数字字符串主排序。

若一个大类含有需要单独展示的稳定特例，用同一贡献的 `namedSubsets` 声明
`ChordCatalogNamedSubset`。章节提供子分组元数据及针对 `ChordInterpretation` 的结构谓词；
目录把匹配候选从父分组抽出、改写其来源分类并按全局 category order 排序。UI 仍只消费通用
`ChordSelectionGroup`，不得按和弦名、符号或章节 id 分支。一个投影候选必须整体落入至多一个
命名子集；若按音响聚合后的多个解释被谓词拆开，目录会拒绝该贡献，章节应先调整投影边界。
当前拿坡里和弦即由小下属章节以此方式从“小下属关系和弦”中单列。

family-scoped projection 只决定“选择器显示几张卡”，不决定详情查询范围。架构调整后，每个
choice 都投影 `ChordSelectionOriginRef + AudibleSonorityKey`，再由全局发现索引查询跨门类解释。
章节知识另行显式声明 `ChordExplanationId` 和路线；目录层不得从符号、quality 或 trace 推断分组。

类别和知识贡献将共同引用唯一 `ChordChapterDescriptor`。现有全局 `category.order` 迁为章节顺序
加章内顺序，禁止选择目录和知识目录分别维护相同数字。

## 3. 发现与平台边界

commonMain 定义贡献协议和 `expect ChordCatalogChapterDiscovery`。JVM actual 扫描
`com.mecon.theory` 下的已编译 class，通过 Java 反射筛选：

- 带 `@DiscoverableChordCatalogChapter`；
- 实现 `ChordCatalogChapterProvider`；
- 是 Kotlin `object`，可通过静态 `INSTANCE` 取得实例。

扫描结果缓存一次。common 测试或其他平台也可直接向 `ChordSelectionCatalog.groups` 注入
provider 列表，不依赖反射。

## 4. 符号、音名与 i18n

- 字母和弦与质量后缀由 `ChordSymbolFormatter` 统一处理；
- 功能罗马数字由 `FunctionalChordSymbolFormatter` 处理，并复用同一 `qualitySuffix`；
- 构成音绝对显示保留 `SpelledPitchClass` 拼写；
- 相对显示以调号对应的大调主音为 1，因此 A 小调 `i` 为 `6–1–3`；
- 小调三和弦章节调用 `NaturalTriads` 的自然、和声、旋律小调并集；严格 AEOLIAN
  `DiatonicChordVocabulary.constructedChords` 不因选择目录而扩域。因此 A 小调目录同时包含
  `ii = 7–2–♯4`、`III+ = 1–3–♯5`、`IV = 2–♯4–6`、`V = 3–♯5–7` 等变体；
- theory 只保存 i18n key，不依赖桌面语言注册表；
- 类别翻译位于 desktop 的中英文 `ExplorationStrings`。

## 5. 验收

新增贡献至少覆盖：

- 反射能发现章节对象；
- 类别 id、顺序及 i18n key 唯一；
- 命名子集不会与父分组重复候选，且选择来源使用子分组 id；
- 目标调性下包含预期功能符号和拼写音；
- 大、小调相对音级正确；
- 选择目录与钢琴卷轴使用同一 pitch-class 集合。

游移和弦贡献还必须覆盖：

- 家族内部 `SoundingClassId` 唯一，不与其他家族全局合并；
- 展平音响下的线路后，全部 `InterpretationId` 都被保留；
- 同类用途按显式 explanation id 只显示一次，全部路线仍被保留；
- 构造线路、连接与来源由章节知识贡献提供，UI 不解析 trace 字符串。

所有贡献还必须覆盖：相同 `AudibleSonorityKey` 能跨章节互查，所选门类优先但不被自动锁定；
自由应用与锁定精确解释都不改变原有选择卡数量。
