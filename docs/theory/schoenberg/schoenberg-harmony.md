# 勋伯格和声学练习接入

> 状态：**共同音原位连接、导和弦、六和弦、四六和弦、七和弦、根音方向与反复、终止式、自由处理及小下属关系和弦独立练习已接入 S2 约束程序**。代码放在独立目录：
> `theory/src/commonMain/kotlin/com/mecon/theory/schoenberg/`。
> 勋伯格练习复用现有 textbook 三和弦知识与 S2 关系约束，但不放进
> `com.mecon.theory.textbook` 或 `docs/theory/textbook/`。
> 规则对分层动态规划的依赖审计、自由写作优先的落地顺序与本分支迁移卡尺见
> [../dynamic-programming-solver.md](../dynamic-programming-solver.md)。

综合练习 builder 现显式使用 `SCHOENBERG_GENERAL`，并继续关闭具体和弦 textbook 模块。
这类程序可显式选择 `LAYERED_DP` 做兼容性与差分验证；当前综合练习规模下完整前缀评分仍偏重，
因此 `AUTO` 暂时回退 `GREEDY_DFS`。待增量 kernel 的性能卡尺通过后再切换默认后端。

当前文件布局：

- `SchoenbergCommonToneExercises.kt`：统一 registry 对外门面、章节/规则 id、descriptor 目录。
- `SchoenbergExerciseModel.kt`：符号和弦、进行、知识标签与练习 descriptor。
- `SchoenbergCurriculumCatalog.kt`：练习定义的唯一元数据目录；集中标题、selection schema、
  continuation 范围、可用选项与稳定 `handlerId`；registry 只登记可复用 handler 类型。
- `SchoenbergRootPositionConnections.kt`：共同音原位三和弦连接规则与练习。
- `SchoenbergLeadingTriadChapter.kt`：导和弦规则与独立练习。
- `SchoenbergFirstInversionChapter.kt`：六和弦规则与独立练习。
- `SchoenbergSecondInversionChapter.kt`：四六和弦的最小原则与独立练习。
- `SchoenbergSeventhChordChapter.kt`：从和弦结构推导不协和音，并生成七和弦五度圈练习。
- `SchoenbergIntegratedTechTree.kt`：综合练习门面。
- `SchoenbergIntegratedStages.kt`：综合阶段 descriptor 与 treatment 集合；program、enumerate
  和 vocabulary 从 descriptor 读取同一配置，不串传按和弦族增长的布尔参数。
- `SchoenbergIntegratedProgramBuilder.kt`：综合练习 typed requirement 与 program 组装。
- `SchoenbergIntegratedEnumerator.kt`：使用有节点/结果预算的贪心 DFS 枚举符号进行；相邻合法性按和弦对缓存，
  知识标签、已用和弦和根音进行用可回退状态增量维护，并用“剩余 N 步能否到终止主和弦”的反向 DP
  提前剪掉死分支。桌面预览传入取消探针，切换章节后旧 DFS 不再占住串行枚举队列。
- `SchoenbergIntegratedTransitionPolicy.kt`：相邻和弦可达性、教学进行与禁忌表接入。
- `SchoenbergIntegratedVocabulary.kt`：按阶段能力构造三和弦、七和弦和变化音词汇。
- `SchoenbergRootMotionAndRepetitionChapter.kt`：大小调通用的根音方向、极值与旋律反复评分。

综合练习默认时间轴按四分音符推进，每四个槽位换入下一 4/4 小节；槽位的
`TimeCode.measure`、输出 `StorageMeasure` 和播放时间线必须覆盖同一小节范围，禁止用超过
小节时值的 beat 把后续槽位留在前一小节。
- `SchoenbergCadenceChapter.kt`：共享终止式策略、正格 / 阻碍终止与终止四六。
- `SchoenbergFreerDissonanceChapter.kt`：自由七音和减五度处理的独立规则档位。
- `SchoenbergDiminishedSeventhChapter.kt`：减七和弦的省略根音属九解释与两层选择练习。
- `SchoenbergAugmentedSixthChapter.kt`：意/德/法增六、游移解释与增六重释；详见
  [在调性的边缘](tonal-frontiers.md)。
- `SchoenbergMinorSubdominantChapter.kt`：小下属关系词汇、拿坡里终止与类拿坡里连接。
- `SchoenbergChordCatalog.kt`：练习级构造收集边界；按实际拼写音集合归并音响，再展开解释目标。
- `SchoenbergHarmonicTreatments.kt`：参考、替代与附加规则族的组合声明。
- `SchoenbergChapterRegistry.kt`：注册 exercise handler，并汇总章节、规则归属和禁忌诊断。
- `SchoenbergExerciseSupport.kt`：共享符号和弦转换、枚举辅助与重复音约束辅助。

### 统一和弦管线

变化音词汇不再各自产出独立 target；旧 `DefinedChordTarget` 已删除。运行路径为：

`构造 recipe → ConstructedChord → ChordCatalogCollector → ChordCatalogEntry → InterpretedChordTarget`

- 收集键保留实际拼写，等音但拼写不同的音响不误合并。
- 同一实际音响可保留多个 `ChordInterpretation`；每个搜索分支只选择一个解释。
- 排布枚举按“音响 + 低音”缓存，功能规则仍按解释分别判断。
- `AllDifferent` 可显式选择按音响、解释或完整目标比较。

副属和弦、无根属九减七和自然音级和弦先进入同一个练习级目录，因此同一组成音的自然音级解释与应用功能解释会共享物理音响，但保留不同功能音角色。完整数据模型见
[../../data_model/chord-construction-and-interpretation.md](../../data_model/chord-construction-and-interpretation.md)。

`HarmonicTreatment` 用 `references`、`substitutesFor` 和 `ruleFamilies` 表达规则组合。例如无根属九参考副属处理、替代普通属功能，并额外加入“所选音降至省略根音”和“变化音平顺进行”，无需复制属功能规则。

exercise descriptor 同时声明选择能力、搜索策略、`chapterId` 与规则前缀。exploration 和禁忌探测都读取该注册信息；无解相邻对通过逐章节约束消融定位责任章节，生成表的注释中写入 `chapters=...`。

## 0. 基础设施检查

已落地：

- S2 `ConstraintProgram` 固定槽数、开放槽位、`SlotDomain` 与三/七和弦混合 arity。
- `AllDifferent` 与 `AdjacentCommonTone` 可在和弦身份层先剪枝，并在排布层解释。
- `DoublingRequirement(required=true)` 可把重复音偏好提升为硬约束。
- `RuleProfile` / `RuleSuppression` / requirement 窗口化可表达一般规则权重和正误对照。
- `exploration` 已有 `schoenberg-exercise` 便捷请求、manifest/FormSpec 与桌面探索入口。

本轮补齐：

- `AvoidDoublingRequirement`：用于六和弦“避免重复三音”、导和弦“避免重复五音”。
- `ChordToneNeighborRequirement`：用两条通用相邻音约束表达 VII 五音预备（前一音保持 4）与解决（后一音下行到 3），已公开为 `ChordToneNeighborSpec`。
- `TargetFeatureBonusRequirement`：综合练习按知识点丰富度排序，尤其奖励导和弦转位等组合知识，已公开为 `TargetFeatureBonusSpec`。
- 勋伯格练习目录：大调分支下暴露共同音、导和弦、六和弦、四六和弦、七和弦，以及四级累计综合练习。
- 练习 descriptor 声明独立 / 综合分组，以及是否要求先枚举符号进行；桌面右侧直接按 descriptor 渲染。

仍未落地：

- `ConstraintProgramSpec` 已公开序列化 `AvoidDoubling` / `AvoidScaleDegreeDoubling` /
  `ChordToneNeighbor` / `TargetFeatureBonus`；`SchoenbergExerciseRequest.exerciseId` 仍作为桌面便捷入口保留。
- 转调分支的第一套共同和弦练习已接入：五度圈选择目标调和公共和弦，共同和弦后必须出现
  原调音阶外的目标调特征音，并以目标调 `V-I` 终止；详见
  [../modulation.md](../modulation.md)。后续半音化转调章节仍为 🚧。
- 练习完成度/解锁状态尚未持久化，当前科技树只用于展示与筛选规则。

本轮补齐（小调 + 无共同音）：

- **小调分支四阶段综合树**（`INTEGRATED_MINOR_*`，挂 `MINOR_BRANCH_RULE_ID`）：镜像大调阶梯（减三和弦 → 六和弦 →
  四六和弦 → 七和弦），综合练习 `program` / `enumerate` 按 `key.mode == AEOLIAN` 追加小调规则，大调路径逐字节不变。
- **无共同音连接**（`NO_COMMON_TONE_MAJOR/MINOR`，大 / 小调各一节点）：放开相邻共同音约束，词汇只取协和三和弦。
- 桌面练习编辑器按练习分支推导 `forcedKeyMode`（小调练习锁小调），规则树按 `descriptor.parentId` 分挂大 / 小调分支。
- 小调禁忌相邻进行表 `forbidden-transitions-minor.txt` 与 `SchoenbergMinorForbiddenTransitionGeneratorTest`（见 §10）。

## 1. 分层原则

- 勋伯格练习层只描述"练习要学生做什么"：输入项、允许词汇表、槽间关系、求解策略。
- 三和弦、原位连接、重复音、四部写作等通用知识仍复用 `:theory` / `textbook` 既有实现。
- `:exploration` 只做框架适配：`SchoenbergExerciseRequest`、manifest 章节、FormSpec、enumerate 结果与输出装配。
- 通用脚本 / MCP 后续若要表达自定义变体，仍可消费同一批 S2 关系约束。

## 2. 第一个练习：共同音原位和弦连接

核心入口：`SchoenbergCommonToneExercises.firstExerciseProgram(...)`（返回 theory 运行时
`ConstraintProgram`）。前端 / API 入口：`SchoenbergExerciseRequest`。

用户只输入 `continuationChordCount`（希望接续的和弦个数），不用指定具体和弦。练习默认：

- 从 I 级三和弦开始；
- 所有槽限定为原位三和弦；
- 每个槽要求重复根音；
- 窗口内和弦不得重复；
- 相邻和弦必须有共同音，且共同音要在同一声部保持；
- 其余声部的"最平顺"由写作求解器既有 motion cost 排序。
- 导和弦（VII 减三和弦）从词汇表排除。

如果调用方希望先确定具体和弦，可调用
`SchoenbergCommonToneExercises.enumerateFirstExerciseProgressions(...)` 取得符号级进行，再把选中的
`SchoenbergSymbolicProgression` 传回 `firstExerciseProgram(..., progression = selected)` 做四部排布。
这种"先 enumerate 和弦，再 solve 排布"的流程也适用于后续不要求用户指定具体和弦的练习。

## 3. 使用的 S2 约束

第一个练习构造运行时 `ConstraintProgram` 时使用以下约束：

- 第 1 槽的 `SlotDomain` 固定为 I 级原位三和弦；后继槽默认开放为调内原位三和弦。
- `DoublingRequirement(slot, ROOT, required=true)`：硬性要求根音重复。
- `AllDifferentRequirement(window=0..end)`：窗口内和弦身份不重复。
- `AdjacentCommonToneRequirement(window=0..end, holdInSameVoice=true)`：相邻槽必须共享并保持共同音。

若已经通过 enumerate 选定符号级进行，会为每个槽固定 `degree / quality / position / arity`；
否则后继槽保持开放，由 S2 在根位三和弦词汇表内枚举。

## 4. 导和弦练习

入口：

- `enumerateLeadingTriadProgressions(key)`：枚举所有“预备和弦 - VII - III”三槽进行。
- `leadingTriadProgram(key, progression?)`：生成四部排布。
- 桌面端先展示全部枚举结果；用户选择一条后，`SchoenbergExerciseRequest.progression` 固定该进行再渲染乐谱。

规则：

- 中间槽固定为 VII 减三和弦；不强加先前教材里的“减三和弦常用第一转位”章节规则。
- 前一和弦必须包含 VII 的五音，并在同一声部保持到 VII 作为预备。
- VII 必须解决到 III。
- VII 的五音必须下行级进解决。
- VII 不重复五音。

## 5. 六和弦练习

入口：

- `enumerateFirstInversionConnectionProgressions(key)`：枚举所有有共同音的“原位三和弦 - 六和弦”与
  “六和弦 - 六和弦”二槽连接。
- `firstInversionConnectionProgram(key, progression?)`：生成四部排布。
- 桌面端同样先展示全部枚举结果，再把用户选中的连接传入 solver。

规则：

- 目标连接必须有共同音，但不硬性要求共同音保持；共同音保持让位于更高优先级的重复音规则。
- 第一转位槽避免重复三音。
- `II6 - VII` 这类连接中，导和弦预备覆盖“避免重复三音”，允许 II6 重复 4 作为预备。

## 6. 四六和弦与七和弦

四六和弦独立练习枚举三槽连接：中间槽固定为四六，前后允许原位或六和弦。章节只声明三条硬原则：不可连续使用四六、根音或低音至少预备一个、解决时低音保持或级进。不启用 textbook 的“终止 / 经过 / 持续 / 同和弦插入”四语境限制。

七和弦独立练习生成 `I - IV7 - VII7 - III7 - VI7 - II7 - V7 - I` 及转位排列变体。根音与七音必须在场，因此需要省略时只可能省略三音或五音。七音的**保持预备**由本章 `inferredDissonanceConstraints` 逐槽推导；**下行级进解决**则抽成**不限转位**的通用 typed requirement `seventhResolutionNeighborRequirements`（`arities={SEVENTH}`，不加 `inversions=`）——独立章节 `includeResolution=true` 仍逐槽表达，综合练习传 `includeResolution=false` 只留这条 typed 硬约束以**避免同一规则两处并存**，且该 typed 解决规则供禁忌表探测器继承（§7）。导七和弦的**第二个不协和音——减五度的五音——不在本章重写**：它是对应导三和弦的五音，交给**不限 arity 的一般导和弦规则**（`leadingTriadNeighborRequirements` / `leadingFifthAvoidDoublings`，三和弦与七和弦共用同一条）同声部预备并下行解决；`inferredDissonantTones` 因此只声明七音。第二转位由通用转位原则推出根音/五音择一预备；第三转位七音落在低音，其强制下行解决反过来锁死后继和弦的低音（转位），由此产生转位敏感的禁忌相邻对（如 `I42` 只能接 `IV6`/`IV65`），落表机制见 §7。**导七和弦（vii°7）与导三和弦同承载 `LEADING_TRIAD` 知识点**（`isLeadingSeventh`）：否则七和弦阶段要求导和弦知识时，vii°7 无法单独满足、会被迫与 vii° 三和弦（同为 7 级、四部难共存）同现而**枚举无解**——含 vii°7 的进行取不到。

> **规则复用原则**：书中三和弦规则在对应七和弦一体适用（七和弦约束 = 对应三和弦约束 + 七音）。故规则的 selector **勿过度限定 arity/转位**（如导和弦五音规则只按 `degrees={7}`、不加 `arities={TRIAD}`），接入新和弦类型时**复用既有 typed 规则、勿另写一份**——新 arity 的 program 把 `leadingTriadNeighborRequirements` 等接进 `chordToneNeighbors`/`avoidDoublings` 即可。规则不限 arity 后，禁忌表探测器会自动把它投影到七和弦相邻对（§7）。

两章关闭 `includeDerivedTextbookConstraints` 并使用空的章节规则模块，只隔离 textbook 专章派生规则；音域、声部交叉、平行进行等通用四部检查仍保留。

## 7. 综合练习与科技树

综合练习使用 `programForExercise(exerciseId, ...)`：

- `schoenberg.integrated.major.leading-triad`：共同音连接 + 导和弦。
- `schoenberg.integrated.major.first-inversion`：共同音连接 + 导和弦 + 六和弦。
- `schoenberg.integrated.major.second-inversion`：前一阶段 + 四六和弦。
- `schoenberg.integrated.major.seventh-chord`：前一阶段 + 七和弦。

四级练习的允许词汇表严格累计：前一级词汇表是后一级的子集。后两级复用独立章节的
四六预备/解决与七和弦不协和音预备/解决约束，不回退到 textbook 的章节特例。
累计能力由 `SchoenbergExerciseDescriptor.harmonicTreatmentIds` 声明，并经
`SchoenbergHarmonicTreatments.registry` 解析；增加新和弦族只注册 treatment 并挂到 descriptor，
无需修改 stage `when` 或在枚举器、词汇表、program builder 间增加新布尔参数。

**只保留一般四部写作规则**：勋伯格模式下所有 章节/综合 program 都以 `ruleModules = emptyList()` +
`includeDerivedTextbookConstraints = false` 构造，去掉 textbook 关于具体和弦的模块与派生命名约束，
只留平行五/八度、声部间距、音域等一般规则。四六和弦与七和弦的教学改由勋伯格章节原则承担，且在综合练习中
多以**软偏好**（`asSoftPreference`）+ 成功 `Annotate` 参与打分与说明，不作硬约束——综合练习的进行是枚举拼接的，
硬章节约束会与四部写作规则相互挤死导致无解。**例外：七音下行级进解决**抽成不限转位的 typed
`seventhResolutionNeighborRequirements` 放进 `chordToneNeighbors` 作**硬约束**（既供求解，又供禁忌表探测器继承，
见下）；预备等其余章节原则仍保持软偏好。为不重复，综合练习对 `inferredDissonanceConstraints` 传
`includeResolution=false`（见 §6）。

**词汇与相邻约束**（枚举侧，防止拼出写不出的进行）：

- 相同根音的和弦（如 I 与 I6 / I7）不排在相邻槽位——只是同一和声的换位，缺乏进行意义。
- **七和弦以根音上行四度（下行五度）解决**：任一七和弦（含导七和弦 `vii°7`）后接的和弦根音须比它高纯四度
  （调内 +3 音级），与导三和弦解决到 III（VII→III 亦是根音上四度）一致。这是勋伯格的教学进行选择、不是四部
  「写不出」，故与「导和弦解决到 III」一样留在 `allowsIntegratedStep` 枚举层，**不进禁忌表**（表只收四部无解的对）。
  七音的下行级进声部解决是另一条正交约束，二者叠加才排除 V7→iii 这类「七音解决对但根音进行错」的进行。
- 减三导和弦的四六和弦（vii°6/4）**不排除**，勋伯格用 IV/ii6 - vii°6/4 - iii。代码里只保留教学性的
  「导和弦解决到 III」（`after.degree == MEDIANT`）；至于哪些**转位**声部写不出（如 vii°6/4 五音在低音
  只能解决到 iii 根位、须由低音=F 的 IV/ii6 预备），由下面的禁忌表数据驱动，不写死。见 `SchoenbergViiSixFourTest`。
- **禁忌相邻进行数据化（转位敏感、规则自动继承）**：四部写作写不出的相邻对由 `SchoenbergForbiddenTransitionGeneratorTest`
  用求解器逐对探测，落到 `theory/src/jvmMain/resources/schoenberg/forbidden-transitions.txt`，运行时 `enumerate` 经
  `SchoenbergForbiddenTransitions.isForbidden` 按 `度数/性质/规模/转位` 读表规避。探测器**不手列规则**，而是从
  `SchoenbergIntegratedTechTree.program(...)` 提取其全部 typed 规则族（完整性/重复/共同音/邻接预备解决）投影到相邻两槽
  （邻接规则：解决钉 before 槽、预备钉 after 槽），故按常规方式新增规则后重刷即自动进表。含导和弦五音预备/解决后，
  例如 `vii°64 => iii6` 在表内、`vii°64 => iii`（根位）不在。因导和弦规则**不限 arity**（§6 复用原则），同一探测也覆盖导七和弦：
  `ii => vii°43` 在表内（五音 F 被 43 逼到低音、ii 低音≠F 无法同声部预备），`ii => vii°7`（根位，F 可在内声部预备）不在；
  `I7 => vii°7` 全转位皆在（I7 不含 F，任何声部都预备不了）。**七音下行解决**同样被投影，故也**转位敏感**：三转位（七音在低音）
  低音被强制下行锁死后继转位——`I42 => IV`/`IV64`/`IV7`/… 在表内，`I42 => IV6`/`IV65`（低音已是解决音 6）不在；上方声部转位
  则在解决音整体缺席时才写不出——`I7 => iii`、`I7 => V`（B 无处下行到 A）在表内，`I7 => IV`（含 A）不在。
  **前提**：这类规则须表达为 typed requirement 落在被投影字段（`chordToneNeighbors` 等）且开放域即存在；per-progression 才生成的
  raw `Constraint` 在 `progression=null` 参考程序里为空、进不了表（`I42=>IV` 一度漏收即此因）。禁忌表穷举已从日常
  `:theory:jvmTest` 分离到按需任务 `:theory:schoenbergForbiddenTransitionTest`；**每新增/修改一类规则后须显式重刷该表**（见 AGENTS.md）。

综合练习还接受 `chordFilters`。一个 filter 可只给音级、和弦规模或转位，也可同时给出多项；
同一 filter 的非空性质在同一和弦上取交集。多个 filter 全部必须满足，并由不同槽位分别匹配，
例如可同时要求“VII 级第一转位三和弦”与“任意第二转位和弦”。枚举与 solve 使用同一筛选语义，
显式提交不匹配的进行会得到结构化约束诊断。

搜索策略：

- 从 I 开始，后续槽按当前知识点开放对应词汇表。
- `AllDifferent` 防止窗口内重复同一和弦身份。
- `AdjacentCommonTone(holdInSameVoice=false)` 保留“有共同音”的搜索框架，但允许六和弦规则打断共同音保持。
- `ChordToneNeighborRequirement` 只在出现 VII 时生效，并通过 `sourceTone/sourceDegrees/direction/candidateScaleDegrees` 声明预备与解决。
- `TargetFeatureBonusRequirement` 奖励新知识点；导和弦第一转位获得额外组合奖励。

展示策略：

- 当前 UI 在勋伯格分支下显示“大调和弦连接 / 小调和弦连接 / 大小调通用 / 转调”。
- 通用分支不锁定调式；大调与小调专属分支仍分别锁定。
- 练习顺序仍应严格从基础连接推进；科技树只是展示和筛选规则的方式。

## 8. 前端入口

探索页规则树新增一级节点"勋伯格和声学"。点击其练习节点后进入 `schoenberg-exercise` 表单，
右侧按 descriptor 分组显示"独立练习"与"综合练习"。大 / 小调专属节点锁定调式，通用节点允许自由切换；
导和弦、六和弦、四六和弦和七和弦独立练习不显示接续和弦个数，只显示 `PROGRESSION_PICKER` 枚举结果；
综合练习以 I + 预备 + VII + III + 终止 I 为最短骨架；末尾原位 I 固定为终止。除终止 I 可重复开头 I 外，其他槽按音级、性质与转位保持和弦身份唯一；不同转位视为不同身份。四个阶段的接续和弦范围依次为 4–6、4–12、5–12、7–12。后续阶段只把新知识标签作为排序优先项，不把它变成排除旧阶段进行的硬条件，因此前一阶段仍是后一阶段的真子集；用户 `chordFilters` 才负责显式收窄。每次最多保留 64 条并限制访问 50,000 个搜索节点。UI 展示排序前 5 条；选择“其他”表示不固定具体进行，求解器在同一总节点预算内依次探测最多 8 条排序候选，首条写不出时继续下一条；显式选择的进行仍独享完整预算。

输出 findings 先列 `schoenberg.*` 教学说明，再列通用调性和声检查。硬约束仍只在违规时发出
VIOLATION；勋伯格章节为已满足的预备/解决原则配套 `Annotate`，因此正确候选也能说明其预备与解决。

## 9. 当前限制

- `AllDifferent` 当前按和弦身份（degree / quality / arity）比较；第一个练习全为根位，因此不区分转位不会造成歧义。
- `AdjacentCommonTone` 要求同一声部保持完全相同音高；跨八度换位不算保持。
- "其余声部最平顺"目前是排序目标，不是独立可序列化 objective。若后续 UI 需要显式权重，再把
  motion cost 提升为 `ObjectiveSpec`。
- 勋伯格新增约束已暴露到通用 `ConstraintProgramSpec`，脚本/外部 API 可直接构造同一批约束；
  `SchoenbergExerciseRequest` 只保留便捷表单语义。

## 10. 小调分支与无共同音连接

完整规则、实现注意事项与小调禁忌表刷新命令见
[minor-and-no-common-tone.md](minor-and-no-common-tone.md)。

## 11. 根音进行方向与反复

`schoenberg.root-motion-and-repetition` 挂在“大小调通用”分支，复用最终综合阶段的完整词汇：
当前调内全部自然三和弦、七和弦及已学习的所有转位；不再要求相邻和弦必须有共同音。后续勋伯格章节也应挂在
该通用分支，除非教材明确限定调式或和弦子集。
根音 / 和弦选择规则的分层、共享判定与单一评分接入规范见
[root-chord-selection-rules.md](root-chord-selection-rules.md)。

根音方向按有向调内级数差分类：`+3/+5` 为上升进行（上四度 / 下三度），`+2/+4` 为下降进行
（上三度 / 下四度），`+1/+6` 为超越进行（二度）。枚举器与运行时约束使用同一分类。上升进行不限制；
超越进行保留但加软成本；下降进行必须还有下一和弦，且从下降前的根音跨两步看，结果不可仍属下降进行。
每个连接还产生带教材原因的 indication，不只报告分类名称。
运行时一律用 `ChordTarget.degree` 计算方向和相似性；不可从自然音阶反查实际根音 PC，否则小调
`vii°` 的升导音根音会被误作“音阶外未知音级”。固定进行先运行 target-only 硬约束预检，确定违规
不生成任何四部候选；合法目标前缀的根音约束结果缓存后供所有 voicing 共用。

反复规则在全曲候选完成后评分：

- 和弦相似性只看根音音级：同根音的不同转位、三 / 七和弦及不同自然音和弦性质均视为类似和弦；
  两次出现之间至少隔开两个和弦。
- 相同根音对构成的进行不可再次出现；因此 `I-ii-…-I6-ii6` 会按类似进行反复排除。
  两条和声反复规则同时进入枚举增量剪枝与运行时约束，不能靠绕过 enumerate 逃避。
- 合法候选再按可解释成本排序：每次超越进行计 10，连续两次超越追加 6；类似和弦的距离成本为
  `18 / 槽位距离`，因此 `I-ii-iii-I6` 虽合法，仍会因两个连续超越与较近的 I 回返排到后面。
- 权重只定义在 `ROOT_PROGRESSION_SCORING_POLICY` 一处；enumerate 的前缀贪心分与四部写作完成候选的
  `RootProgressionPreference.scoreDelta` 均调用同一个 `RootProgressionScoringPolicy.score`，后者只额外叠加声部写作成本。
- 搜索按当前前缀累计成本贪心排列下一和弦，优先深入低分分支；同时收集最多 `8× maxResults`
  （上限 512）的候选池再统一排序截断，避免“先按 DFS 截断、后打分”遗漏更好的后续分支。
- 高音最高点唯一是硬约束，低音最低点唯一是软偏好。
- 极值已重复且剩余槽位在声部音域内不可能产生更外侧音高时，硬规则立即剪枝，不等完整候选。
- 四个声部分别运行非重叠自匹配；用音程差序列同时识别原样与移位反复。
- 反复成本随片段长度平方增长、随两次出现距离缩短而增加；高音与低音的权重为内声部两倍。

## 12. 终止式与自由处理

终止式、阻碍终止、终止四六以及后续七和弦 / 导和弦的自由处理规则见
[cadence-and-free-dissonance.md](cadence-and-free-dissonance.md)。终止式由枚举与求解共享同一符号策略；
严格与自由处理使用各自的 typed 规则投影和禁忌相邻进行表，旧章节不会被新规则反向放宽。

## 13. 副属和弦
中古调式派生、先选具体和弦再枚举进行、综合科技树阶段与自由求解器接入见 [secondary-harmony.md](secondary-harmony.md)。

## 14. 减七和弦

完全减七和弦的对称多重解释、免预备规则、变化音级进与单独/综合/自由求解入口见
[diminished-seventh.md](diminished-seventh.md)。

## 15. 小下属关系和弦

同主音 / 下属自然小调借用、拿坡里终止及类拿坡里局部连接见
[minor-subdominant.md](minor-subdominant.md)。
