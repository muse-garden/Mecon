# 乐理库 (Theory)

> 模块：`theory/src/commonMain/kotlin/com/mecon/theory/`
>
> **状态**：🚧 部分实现。已有 `Chord`、`Scale/Key`、和弦识别、和弦符号格式化、调内自然三和弦查询；扩展和弦质量与高阶和声功能分析仍在推进。

自由和声求解器正在作为统一底座实施：调性、和弦词汇与调号正交建模，支持任意固定声部，
习惯进行编译为 `ConstraintProgram`，勋伯格/textbook/爵士作为可组合 preset。设计与验收见
[free-harmony-solver.md](free-harmony-solver.md)；自由写作优先的分层动态规划后端设计见
[dynamic-programming-solver.md](dynamic-programming-solver.md)；数据模型见
[../data_model/harmony.md](../data_model/harmony.md)。

共同和弦转调工具与第一套勋伯格转调练习见 [modulation.md](modulation.md)；相差三 / 四个
升降号的转调与持续音架构见
[schoenberg/distant-modulation-and-free-practice.md](schoenberg/distant-modulation-and-free-practice.md)，
自由练习领域、自动写作与 refine 分期见 [领域基线](schoenberg/free-practice.md)、
[交互改造](../exploration/free-practice-auto-writing.md) 和 [窗口求解](free-practice-window-voicing.md)。
章节向通用和弦目录贡献新和弦类别的协议见
[chord-catalog-contributions.md](chord-catalog-contributions.md)。

## 1. 已实现

### Chord (`theory/.../Chord.kt`)

```kotlin
enum class ChordQuality {
    MAJOR, MINOR, DIMINISHED, AUGMENTED,
    DOMINANT_7, MAJOR_7, MINOR_7,
    HALF_DIMINISHED_7, DIMINISHED_7,
    SUS2, SUS4,
    // ... 扩展和弦枚举已定义，音程集合尚未完整
}

data class Chord(
    val root: Pitch,
    val quality: ChordQuality,
    val bass: Pitch? = null,     // 转位低音
)
```

`Chord.pitchClasses` 目前对三和弦、挂留和弦与常用七和弦返回明确 pitch-class 集合；其余扩展质量暂时回退到大三和弦，后续需要补全到完整 interval 表。

### Scale / Key (`theory/.../ScaleAndKey.kt`)

```kotlin
enum class Mode {
    MAJOR, NATURAL_MINOR, HARMONIC_MINOR, MELODIC_MINOR,
    DORIAN, PHRYGIAN, LYDIAN, MIXOLYDIAN, AEOLIAN, LOCRIAN,
    // ... 16 种，枚举已完整
}
```

`Scale.fromMode(root, mode)`、`Scale.major(root)`、`Scale.minor(root)` 已实现。`Mode` 内记录半音结构；中古调式按同一调号集合的起始音级建模：大调/IONIAN 从 1 级开始，小调/AEOLIAN 从 6 级开始，DORIAN/PHRYGIAN 等保留对应起始音级，方便后续扩展。

乐谱上的调号本质只记录升降号数量；theory 用 `KeySignatureMode` 显式解释同一调号：

```kotlin
Key.fromKeySignatureFifths(0, KeySignatureMode.MAJOR) // C major
Key.fromKeySignatureFifths(0, KeySignatureMode.MINOR) // A minor
```

### NaturalTriads (`theory/.../NaturalTriads.kt`)

`Key.naturalTriads` 返回结构化的 `NaturalTriad`，包含：

- `degree`：调内音级（小调 A 为 1、B 为 2...）
- `signatureDegree`：调号集合中的音级（A 小调的 A 是 C 调号集合的 6 级）
- `chord` / `root` / `quality`：自然三和弦及其大、小、减、增性质
- `scaleForms`：小调中来自自然/和声/旋律小调的来源
- `minorAlterations`：是否使用小调相对调号集合的升 4、升 5

大调自然三和弦为 7 个：`I ii iii IV V vi vii°`。小调合并自然、和声、旋律小调后为 13 个：
`i ii° ii III III+ iv IV v V VI vi° VII vii°`。

常用查询：

```kotlin
val key = Key.minor(PitchClass.A)
key.isNaturalTriad(Chord(PitchClass.E, ChordQuality.MAJOR))
key.minorAlterationsUsedBy(Chord(PitchClass.E, ChordQuality.MAJOR)) // RAISED_5
NaturalTriads.possibleKeys(Chord(PitchClass.D, ChordQuality.MINOR))
NaturalTriads.possibleKeySignatures(Chord(PitchClass.E, ChordQuality.MAJOR))
```

## 2. 设计阶段 🚧

### 2.1 和弦识别

```kotlin
// 目标接口（设计草图）
object ChordRecognizer {
    // 从一组音高推断最可能的和弦（考虑等音异写）
    fun recognize(pitches: List<Pitch>): List<ChordRecognitionCandidate>

    // 从 pitch-class 集合识别，可传入低音以保留转位
    fun recognizePitchClasses(pitchClasses: Collection<Int>, bass: Int? = null): List<ChordRecognitionCandidate>
}
```

实现已落在 `theory/.../ChordRecognizer.kt`：识别先按 pitch class 半音集合匹配；若没有精确匹配，再按常规 tertian 和弦推断缺失音。五音缺失时可根据三音定到具体大/小/七和弦；三音缺失时返回多个候选（如 `C-G` → `C` 与 `Cm`，分别缺三音 `E` / `Eb`）。候选通过 `missingTones` 暴露缺失的和弦音级与 pitch class，供 UI 展示。

`recognize(pitches)` 会保留输入音高的实际拼写，并在普通和弦候选上通过 `enharmonicSubstitutions` 标出等音替代（例如整体更像 `Db-F-Ab` 时，输入 `C#-F-Ab` 会标为根音 `C#4 -> Db4`）。减七和弦、增三和弦因对称结构不判断替代音高；多个等价根音候选会优先按最低音/低音收敛为单个候选，便于桌面插件直接添加。

桌面和弦分析插件只从 `eventSelection` 抽取音高并调用 `ChordRecognizer`；识别与缺音判断不放在 desktop 层。

### 2.2 和弦符号显示

```kotlin
object ChordSymbolFormatter {
    fun formatSymbol(
        chord: Chord,
        style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
        keySignature: KeySignature = KeySignature.C_MAJOR,
    ): ChordSymbol

    fun format(
        chord: Chord,
        style: ChordSymbolDisplayStyle = ChordSymbolDisplayStyle.LETTER,
        keySignature: KeySignature = KeySignature.C_MAJOR,
    ): String
}
```

和弦对象到展示符号的映射集中在 `theory/.../ChordSymbolFormatter.kt`。`formatSymbol()` 返回分段协议 `ChordSymbol(parts)`，每段包含 `text`、`role`（`ROOT / QUALITY / SEPARATOR / BASS / GAP`）和预留的 `placement`（`BASELINE / SUPERSCRIPT / SUBSCRIPT`）。`format()` 只是 `plainText` fallback，供暂不支持富文本的渲染路径使用。

当前支持：

- `LETTER`：字母和弦符号，如 `C`、`Dm`、`G7`、`C/E`。
- `SCALE_DEGREE`：按当前调号换成相对级数，如 C 大调下 `C` → `1`、`Dm` → `2 m`、`G7` → `5 7`、`C/E` → `1/3`。级数根音与质量后缀拆成不同 part，中间的 `GAP` 避免 `5` + `7` 挤成 `57`。

特殊质量后缀也是 theory 协议的一部分，例如减七 `°7`、半减七 `ø7`、属七降五 `7♭5`。插件与 desktop 不再维护自己的 suffix 表，只选择显示形式并传入当前 `KeySignature`。

### 2.3 和声进行分析

```kotlin
object HarmonicProgressionAnalyzer {
    fun analyze(chords: List<Chord>, key: Key): List<HarmonicFunction>
    // HarmonicFunction = { chord, romanNumeral, function: Tonic/Subdominant/Dominant }
}
```

依赖完整的 `Chord.pitchClasses` 与 `Key.diatonicChords` / `Key.naturalTriads`。

### 2.4 固定声部与音程

第一阶段四部和声基础框架已放在 `theory`：

- `SpelledInterval`：拼写敏感音程，区分增三度与纯四度，并提供等效音程判断。
- `FixedVoiceScore`：从 `RuntimeScore` 载入固定声部视图，校验每行谱表声部数与单声部单音约束，支持同声部相邻事件与纵向同时音查询。

详见 [four-part/README.md](four-part/README.md)。

### 2.5 旋律事实与教材规则

`MelodyAnalysis` 提供通用旋律事实：最高点、相邻音程、级进比例、走向变化、音级、下行音阶片段与三和弦外形。它不判断某条教材规则是否违反，也不定义平行的旋律事件包装；调用方通过 `pitchOf` 直接传 `Pitch`、`FixedVoiceScoreEvent` 等原始条目。

具体乐理书规则放在 `textbook.MelodyTextbookRules`：当前覆盖唯一高潮点、主要级进、旋律曲线清晰、跳进限制、连续跳进三和弦外形、7 级与 4 级倾向音解决。和声写作中，唯一高潮点只检查 `SOPRANO`，其余旋律原则检查所有固定声部。

新增教材内容时遵循“通用事实 API → 教材规则适配”的拆分，严重度按 [../ai/roadmap.md](../ai/roadmap.md) 的 `HARD` / `SOFT` / `HINT` 软约束设计，不把书中“必须/避免”直接写成通用布尔逻辑。

通用 theory 文档见 [melody-analysis.md](melody-analysis.md)，教材实现文档见 [textbook-melody-rules.md](textbook/textbook-melody-rules.md)。

### 2.6 声部进行事实与四部和声规则

`VoiceLeadingAnalysis` 基于 `FixedVoiceScore` 建立所有声部变化点上的纵向快照，因此一个声部持音、另一个声部中途移动时，交错与间距检查仍按真实同时发声关系判断。它提供密集/开放排列、声部交错事实、声部对运动分类、外声部边界交错和非低音相邻声部间距等通用能力。

具体四部和声教材禁则放在 `textbook.FourPartTextbookRules`：当前覆盖外声部边界交错、非低音相邻声部超过八度、可配置人声/器乐音域、平行五度/八度、不相等五度和隐伏五度/八度。默认人声音域为 Soprano `C4-G5`、Alto `G3-D5`、Tenor `C3-E4`、Bass/Baritone `E2-C4`。

详见 [textbook-four-part-rules.md](textbook/textbook-four-part-rules.md)。

### 2.7 原位三和弦连接规则

`textbook.RootPositionTriadRules` 接入原位三和弦写作规则。它不做调性构建或和弦识别，而是消费调用方给定的 `Key`、前后 `NaturalTriad` 与当前 `FixedVoiceTransition`：

- 适用性：若低音不是根音，原位规则返回 not applicable，由调度器切换到转位连接规则。
- 章节约束包：和弦音完整性、入门阶段重复根音、避免重复导音等由
  `TextbookTriadConstraintPreset` 编译为 `ConstraintProgram` requirement。
- 常用连接模式：同和弦反复、根音四（五）度、三（六）度、二（七）度关系均以 `INDICATION` 标出，不把其他合法写法误判为错误。
- 禁则：小调属和弦到六级时，升 5 不可进行到 4。

详见 [textbook-root-position-triad-rules.md](textbook/textbook-root-position-triad-rules.md)。

### 2.7.1 三和弦第一转位规则

`textbook.FirstInversionTriadRules` 接入三和弦第一转位写作规则，并由
`TextbookTriadWritingSolver` 支持原位与第一转位混合枚举。

- 第一转位用于丰富低音线条；低音旋律不满足旋律写作要求时，可考虑加入转位和弦。
- 第一转位重复音可依据音响效果自由选择，但仍不应重复导音。
- 减三和弦在古典主义时期几乎只使用第一转位。
- 大调中，原位属和弦之后不能接六级小和弦；该禁则可作为错误规则试听目标。

详见 [textbook-first-inversion-triad-rules.md](textbook/textbook-first-inversion-triad-rules.md)。

### 2.7.2 三和弦第二转位规则

`textbook.SecondInversionTriadRules` 接入三和弦第二转位（四六和弦）写作规则。
第二转位不自由枚举为低音线条资源，而要求放进终止、经过、持续音或同和弦转位插入语境。

- 终止四六识别 `I(46)-V`，探索模式示例扩成 `I(46)-V-I`。
- 经过四六要求低音级进通过中间的四六和弦。
- 持续音四六要求在持续低音上装饰前后相同的原位三和弦。
- 四六和弦优先重复低音，也就是和弦五音；经过与持续音用法还会提示上方声部避免跳进。

`ProgressionRequest.policyId = "second-inversion-triads"` 会保底三和弦语境，并由
`TextbookTriadWritingSolver` 在完整候选上检查孤立第二转位。

详见 [textbook-second-inversion-triad-rules.md](textbook/textbook-second-inversion-triad-rules.md)。

### 2.7.3 属七和弦规则

`textbook.DominantSeventhRules` 接入属七和弦写作规则，并由 `TextbookSeventhWritingSolver`
支持七和弦目标与转位枚举。该 solver 只把七和弦槽编译为 `ConstraintProgram`，与三和弦
共用 `ChordTarget` 候选工厂、规则调度器和 beam search，不再维护独立求解管线。

- 七音通常下行级进解决；上行解决作为错误对照入口，例外后续补充。
- 小调属七必须含升导音，不能把自然小调 v7 称为属七。
- 原位 `V7-I` 覆盖省略五音的 `I`、不完全 `V7` 到完整 `I`、内声部导音到完整 `I`，以及完整 `I` 引发平行五度的错误对照。
- `V7-VI` 阻碍进行、转位 V7 倾向解决、`V42-I6` 与七音预备已注册为教材规则。
- II7、大小调导七和弦、七和弦省略准则与 `4-7-3-6-2-5-1` 五度圈七和弦模进也已接入。
- `CellOutput.comparisonGroups` 支持正误候选成对输出，桌面探索页会标出“正确例 / 错误例”。

详见 [textbook-dominant-seventh-rules.md](textbook/textbook-dominant-seventh-rules.md)。

### 2.7.4 勋伯格和声学练习

勋伯格和声学练习放在 `theory.schoenberg`，不混入 `textbook` 包。已接入共同音原位连接、
导和弦预备与解决、六和弦连接、综合练习及第一套共同和弦转调练习。用户通过 `SchoenbergExerciseRequest.exerciseId`
选择知识点；独立练习可先 enumerate 全部符号进行，再把选中的 `progression` 传回渲染乐谱。
theory 层以门面 + 章节文件组织，负责声明练习分组、枚举可用符号进行并构造运行时 `ConstraintProgram`；`exploration`
只负责 manifest/FormSpec、枚举结果传递和输出装配。

详见 [schoenberg/schoenberg-harmony.md](schoenberg/schoenberg-harmony.md)。

### 2.8 写作任务与局部规则检查

写作引擎基础设施放在 `TheoryRule.kt`、`WritingTask.kt`、`WritingSolver.kt` 与 `FixedVoiceWritingSolver.kt`：

- `RuleFinding` 扩展旧 `RuleDiagnostic`，可表达违规、警告、提示与正确写法标记，并携带主锚点与相关锚点。
- `RuleApplicability` 用于区分“规则不适用”和“适用后发现违规”；调度器应根据适用性选择规则集。
- `RuleProfile` 可覆盖严重度、关闭规则，并用 `RuleSuppression` 调解互相解释同一锚点的 finding。
- `RuleProfile.requirements` 支持探索模式把用户勾选的规则编译为 `REQUIRE_INDICATION` /
  `REQUIRE_VIOLATION` / `FORBID`，由固定声部写作候选空间在评分阶段统一执行。
- `WritingTask` 将固定材料、生成时间线、写作目标、规则 profile 与搜索参数拆开，避免把任务绑定成“四部和声连接”。
- `WritingTaskPlan` 表示多阶段写作流水线，支持把前一阶段输出作为后一阶段的 fixed material。
- `CandidateSpace` 把候选枚举从搜索器中抽出；指定和弦四部连接、给旋律配和声、复调写作都通过不同候选空间实现。
- `ScoredCandidateSpace` / `BeamSearchSolver` 提供第一版 top-K 搜索，按调解后的 `ScoreBreakdown` 排序并剪去 `HARD` finding。
- `FixedVoiceWritingCandidateSpace` 集中固定声部写作的状态、事件合成、局部规则调度与评分；章节只提供 target provider、候选工厂和 rule provider。
- 固定声部 state 会缓存已应用候选产生的纵向 finding 与 transition finding，评分时只补全需要全局视野的 score-level 规则。
- `FixedVoiceTransition` / `TransitionContext` 支持局部检查。搜索候选应用后应只检查受影响的纵向 slice 与前后 transition，不在搜索内层扫描全谱。

`RootPositionTriadSolver`、`TextbookTriadWritingSolver` 与 `TextbookSeventhWritingSolver` 均为兼容门面：
先把章节输入编译为 `ConstraintProgram`，再复用同一候选工厂、规则调度与搜索流程。

详见 [writing-engine.md](writing-engine.md)。

### 2.9 规则目录与探索模式入口

`RuleCatalog` 为教学规则提供稳定 id、名称 key、章节、可选性、错误示例开关与互斥/附属关系。
首个目录是 `textbook.RootPositionTriadRuleCatalog`，供探索模式规则选择器和
`:exploration` 请求执行器共同消费。

详见 [rule-catalog.md](rule-catalog.md) 与 [../exploration/README.md](../exploration/README.md)。

### 2.10 求解器 API 与约束程序 🚧 部分实现

统一协议包含 `describe / enumerate / solve / refine / check` 五入口；S1 已落地，S2 的核心
`ConstraintProgram` 求解路径部分落地，公开 refine 与 check 仍在推进。规则场景、约束程序、
多样化搜索、注册制 `ChordTarget` 架构、脚本沙箱和装饰化层分别见
[solver-api.md](solver-api.md)、[rule-scenes.md](rule-scenes.md)、
[constraint-program.md](constraint-program.md)、[diverse-search.md](diverse-search.md)、
[dynamic-programming-solver.md](dynamic-programming-solver.md)、
[constraint-architecture.md](constraint-architecture.md)、
[脚本设计](../exploration/scripting.md) 与 [figuration.md](figuration.md)。

### 2.11 其他计划功能

求解器与教材章节的后续工作已按优先级整理在 [roadmap.md](roadmap.md)
（七和弦章场景化迁移、S2 约束程序、勋伯格和声学接入、半音化和弦、求解器健壮性）。其余：

- 更完整的扩展和弦 interval 表
- 移调工具（`Pitch.transpose` 已有，上层封装缺失）
- 后续教程章节的连接类型库与搜索候选空间

## 3. 与插件的关系

乐理库的分析结果通常由 `ChordRecognizer` 等入口生成，作为插件输出写入 `PluginTrack`，再由
渲染层读取并标注到谱面。

详见 [../plugin/custom-track.md](../plugin/custom-track.md)。

## 4. 扩展指引

- 新增 `ChordQuality`：在枚举中添加条目，在 `Chord.toPitches()` 中实现对应音程集合
- 新增 `Mode`：在 `Scale` 伴生对象中添加工厂函数，提供正确的音级列表
- 测试：`theory/src/commonTest/` 中为每个质量/调式写 round-trip 测试（`toPitches()` → `recognize()`）
