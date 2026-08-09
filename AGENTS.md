# AGENTS.md - Mecon 开发规范

## 项目概述

Mecon 是一款基于 Kotlin Multiplatform 的专业音乐分析应用，集乐谱编辑、理论分析与 AI 辅助创作于一体。当前以桌面端（Compose for Desktop）为主，Web 五线谱编辑通过共享 KMP 会话提供。

**技术栈**：KMP 2.1.0 · Compose for Desktop · Gradle 9.0 · kotlinx.serialization + kaml · Coroutines + Flow

详细架构见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，完整文档索引见 [docs/README.md](docs/README.md)。

## 核心设计原则

1. **不可变数据**：所有数据类使用 `data class` / `value class`，字段用 `val`
2. **四层架构**：严格遵守 `Storage → Runtime → Computed → Render Geometry` 层级划分，Renderer 只负责排版，不生成乐谱元素——见下方 ⚠️
3. **类型安全**：用 `@JvmInline value class` 包装基本类型（`EventId` / `TrackId` 等），避免参数混淆
4. **避免重复逻辑**：映射逻辑放在与之最相关的位置（如枚举类型本身），其他位置委托访问；不适合直接复用时先询问是否重构
5. **序列化**：存储层数据类加 `@Serializable`；Storage 层只含源字段，不含对象引用

## ⚠️ 乐谱编辑功能必须同步接入多端

完整接入顺序与验收门禁见
[docs/score-editing-multiplatform.md](docs/score-editing-multiplatform.md)。以下为强制约束：

- **共享会话是唯一业务入口**：新增乐谱编辑能力先在
  `features/score-editing/src/commonMain/` 定义 `ScoreEditIntent` 并接入 `ScoreEditingSession`；
  需要新算法时放入 `core/.../engine/edit/`。桌面、Web、后续移动端只做平台 adapter，不得各写
  音乐规则、状态变换或 undo/redo。
- **Web 只保留轻量壳层**：React/JavaScript 可负责控件、文件/恢复、Canvas/SVG 命中、pointer
  像素到稳定 ID/音乐坐标的映射、瞬时 preview、键盘/Web MIDI adapter；所有持久化写入必须以
  普通 JSON intent 进入 Worker，再经 Kotlin/JS facade 调用 `ScoreEditingSession`。禁止在 Web
  直接改 `StorageScore`，或实现时值拆分、变音记号、符杠、连音组等业务逻辑。
- **UI 过滤不是业务校验**：平台可为体验过滤候选，但 session 必须独立校验 revision、目标存在性、
  参数与结构约束；像素、数组下标和帧内对象引用不得进入 intent。
- **一次功能必须完成整条链**：同步核对数据模型/兼容读取、core immutable edit、协议 codec、session
  effect 与历史边界、Computed 生成职责、renderer metadata/hit box、continuous/paginated splice、
  桌面 adapter、Web pointer/keyboard 入口和相关文档。修改 Storage 模型仍须先更新
  `docs/data_model/`。
- **跨端等价是完成条件**：JVM/JS 重放同一份 `features/score-editing/testdata/intent-trace.json`
  比较 score、revision、selection、effect、`nextInputPosition` 与 `scoreChanged`；覆盖 stale/no-op、
  失败原子性、单历史项、undo/redo 选择恢复和 render hint。**新能力必须往该 trace 追加步骤**，
  否则不受跨端保护；重刷只从 JVM 侧 `-Pscoreediting.trace.write=true`。Web 还须有真实
  Playwright pointer/keyboard 路径；涉及文件时用浏览器导出的 `.mecon` 做桌面回读门禁。
- **桌面普通记谱入口已收敛，禁止回退**：`ScoreSession` / `EditableScoreHost` 的音符、结构、表情、
  几何与选择编辑都应 dispatch `ScoreEditIntent`。`applyStorageEdit` 仍服务插件、配器、缩谱等尚未纳入
  score-editing 协议的文档域，不是新增记谱旁路的依据；新记谱能力一律走 `dispatchSharedEdit` + intent。
- 若某端明确不接入，必须在能力矩阵记录范围与原因；不得只完成桌面实现后静默遗漏 Web。

## ⚠️ 自由练习功能必须经共享会话

完整流程见 [docs/exploration/free-practice-extension-guide.md](docs/exploration/free-practice-extension-guide.md)，
Web 构建运行见 [docs/web-development.md](docs/web-development.md)。以下为强制约束：

- 自由练习的持久化操作、统一选择、历史、后台结果与 typed view 只在
  `features/free-practice/commonMain` 定义；Desktop/Web 均 dispatch `FreePracticeIntent`。
- 普通谱面编辑用 `FreePracticeIntent.Score` 包裹内层 `ScoreEditIntent`；复音上限、手工事件来源和
  workspace/score 原子历史由 `FreePracticeSession` commit policy 负责，平台不得补第二次提交。
- Web 完整谱面与自由练习必须复用 `@mecon/web-renderer/editor/react` 的 `ScoreEditor` 和
  `useScoreEditorController`。工具栏通过 profile/hidden/slot 配置，禁止在应用内复制 surface、controller、
  inspector 或音乐命令。
- 重 CPU 写作、教学目录和 finding 使用带 requestId/baseRevision/fingerprint 的独立后台 channel；
  结果必须回到 session 校验，React 不直接接收并合并领域结果。
- 新能力必须追加 `features/free-practice/testdata/practice-trace.json` 并让 JVM/Kotlin-JS 重放同一流程；
  当前该 trace 由开发者显式编辑并先经 JVM 校验，`-Pfreepractice.trace.write=true` 尚无生成器。
- 钢琴卷轴当前明确保留桌面实现；除非任务明确把它纳入范围，不要借自由练习改动扩张或复制其旁路。

## ⚠️ Renderer 与 Computed 层职责划分

**Computed 层**决定"是否生成"每个乐谱元素（小节线、谱号、调号、临时记号等），输出 `ComputedBarline / ComputedClef / ComputedKeySignature / ComputedTimeSignature`。

**Renderer 层**只做排版：从 `ComputedScore` 读取元素，计算坐标与间距，生成 `RenderCommand`。

- ❌ 不在 Renderer 中判断"是否需要小节线"
- ❌ 不在 Renderer 中计算临时记号、符杠分组等音乐逻辑
- ✅ 发现 Renderer 中有元素生成逻辑 → 迁移到 `ComputeEngine`

### 区间符号排版与跨行吸附

- 房子、8va/8vb、发夹、渐变速度等区间符号必须进入
  `StaffAttachmentLayoutComputer` 的统一区间附件管线，参与横向碰撞、行优先级、系统拆段和
  staff extra extent 计算；禁止在 `StructuralElementRenderer` 中用固定 Y 单独绘制房子。
- 上方区间符号的行优先级由附件类型集中定义：房子位于其他区间符号最外层（最上方），
  不在各 Renderer 中各自添加常量偏移。
- 拖动区间符号或导航记号并吸附小节线时，先用各系统五线谱核心区
  （`staff centerY ± 2 staff spaces`）锁定指针所在行，再只在该系统的 measure bounds 中
  寻找最近边界；系统核心区必须投影到最终显示坐标后与原始指针比较，分页模式下禁止先把
  指针转换为全局谱面 Y 再反推系统。禁止使用包含附加符号/加线音的扩张
  `SystemNode.topY/bottomY` 判定行；扩张带可能互相重叠并导致吸附到相邻行。
- 导航记号跨系统拖动时，预览位移包含源系统到目标系统的 Y 距离；提交到目标小节线后必须
  扣除 `targetAnchorY - sourceAnchorY`，只持久化相对目标谱表的局部 `dy`。否则目标系统锚点
  和存储偏移会各应用一次系统距离，表现为向下/向上都多跳一行。
- 分页增量附件候选按系统的 1-based `measureRange` 分片。房子的索引键必须使用
  `ending.startMeasure`，不能直接使用锚点 `time.measure`：第一房子锚在谱首边界时后者为 0，
  会被系统 `1..N` 的候选查询漏掉，并在 `patchSystems` 替换旧附件时误删。

## ⚠️ 在 UI 中使用 SMuFL 字符

用 Bravura 字体渲染 SMuFL 字形（音符、变音记号等）时务必注意，**完整说明见 [docs/ui/desktop.md](docs/ui/desktop.md) §9**：

- SMuFL 码位在 Unicode 私用区，编辑器 / 终端 / `Read` 工具中**显示为空白**——核对码位请查 `apps/desktop/src/main/resources/bravura/glyphnames.json`，勿靠肉眼。
- 改含 PUA 字符的源码时基于文本的匹配可能失效，勿盲目覆写常量；码位用 `\uXXXX` 集中成命名常量（如 `LeftToolbar.Smufl`）。
- 居中不要用 `Text`，要 `rememberTextMeasurer` 测量后在 `Canvas` 上按基线 `drawText`（见 `MusicGlyph` / `glyphBias`）。
- 必须经 `rememberBravuraFont()` 取字体；无合适字形时手绘（连音线）或退化到近似字形（longa/maxima 用 mensural 符头）。

## ⚠️ 大乐谱编辑热路径

完整复盘与验收项见
[docs/performance/large-score-editing.md](docs/performance/large-score-editing.md)。以下为强制约束：

- **Compose 主线程禁止全谱工作**：不得在 composable、`remember(score)`、状态 getter 或指针
  回调中执行 `ScoreToMidiConverter.convert`、播放时间线构建、全事件扫描 / 排序等 O(score)
  工作。用后台 `produceState` / worker 生成不可变帧；仅在对应 UI / 功能活跃时计算，新帧完成前
  保留旧帧。纯 CPU 任务若不协作取消，须在启动前防抖，必要时使用串行 conflated worker。
- **大不可变帧只用引用 identity**：`RuntimeScore`、`ComputedScore`、`RenderResult` 等进入
  Compose key 或“最新值”状态时，使用 `rememberIdentityKey` /
  `rememberReferentialUpdatedState`；禁止让 `rememberUpdatedState` 或普通结构相等在 UI 线程
  递归比较整谱。
- **新增渲染元素必须维护 splice 契约**：新增 / 改变 `RenderElementType` 时，同步核对
  continuous 与 paginated splice 能力表、系统归属、局部重生成和缓存平移复用，并添加
  “含该元素的普通音符编辑仍 `spliced=true` 且与全量渲染等价”测试。未知 / 插件元素继续
  fail-safe，禁止只扩白名单而不证明等价。
- **更新提示以完整结果为准**：流式首个页面到达不代表一次 render generation 完成；必须到
  完整 `RenderResult` 发布后才能解除交互保护、隐藏“乐谱更新中”。
- **性能日志按阶段归因**：`SideEffect` 总耗时可能包含同批父级 / 兄弟 composition；先用
  分段探针定位主线程阶段，再判断 renderer。大谱普通非 reflow 编辑应保持
  `streaming-incremental(hint)`、`spliced=true`，不得出现未解释的 `cachedRichUnsafe`。

## ⚠️ 勋伯格和声练习（Schoenberg 综合练习）

### 综合练习使用统一调式入口

- **后续新增的综合练习禁止拆成大调 / 小调两个 exercise id、rule id 或 descriptor**。每个教学阶段只设
  一个综合练习入口，挂 `GENERAL_BRANCH_RULE_ID`；调式由练习内的调性选择器和 `Key.mode`
  决定，`enumerate` / `program` 在内部按当前调式生成词汇与规则。
- 禁止新增 `INTEGRATED_MAJOR_*` / `INTEGRATED_MINOR_*` 成对常量。测试必须用同一个 exercise id
  分别覆盖大调与小调。大小调禁忌表仍可因规则与词汇不同而分别探测和存储，但不得因此暴露成两个练习。
- 现有历史综合阶段可在相关重构时逐步迁移；任何新章节从一开始就使用统一入口，不再延续旧的分支拆分方式。

勋伯格章节/综合练习在 `theory/.../schoenberg/` 生成两部分：**枚举**（`enumerate*`，拼出和弦进行）与**求解**（`ConstraintProgram` → 四部写作）。二者协同时有四条硬约束：

1. **只保留一般四部写作规则**：开启勋伯格模式时，去掉 / 降级 textbook 关于**具体和弦**的规则（`ruleModules = emptyList()`、`includeDerivedTextbookConstraints = false`），只留平行五/八度、声部间距、音域等一般规则；具体和弦的教学由勋伯格章节原则（软偏好 + finding）接管。新增章节 program 时照此配置，勿让 textbook 的 V7/四六语境等硬规则与枚举拼接的进行相互挤死。

2. **规则勿过度限定适用范围，接新和弦类型优先复用旧规则**：书中原则——三和弦的规则在**对应七和弦**一体适用（七和弦约束 = 对应三和弦约束 + 七音预备解决）。故：
   - **勿把规则钉死到某一 arity/转位**：selector 只写真正必要的维度。如导和弦五音的同声部预备/解决/禁重复（`leadingTriadNeighborRequirements`/`leadingFifthAvoidDoublings`）只按 `degrees={7}` 选取，**不加** `arities={TRIAD}`——这样 `vii°` 与 `vii°7` 自动共用同一条规则。曾因加了 `arities={TRIAD}`，`ii=>vii°64` 能推出禁忌而 `ii=>vii°43` 推不出（2026-07-21 修）。**转位轴同理**：七音下行级进解决（`seventhResolutionNeighborRequirements`）只按 `arities={SEVENTH}` 表达、**不加** `inversions=`。规则本身不分转位；「相邻写不出」的**转位敏感性由探测器涌现**——三转位（七音在低音）低音被强制下行锁死后继低音/转位（`I42` 只能接 `IV6`/`IV65`），上方声部转位则在解决音缺席时才写不出（`I7=>iii`、`I7=>V`：B 无处下行到 A）。曾错钉到 `inversions={THIRD_INVERSION}`，漏掉上方声部那批写不出对（2026-07-21 修）。
   - **勿在新和弦类型里重写已有规则**：七和弦章节 `inferredDissonantTones` 只负责**七音**；减五度（导七和弦的五音）是其对应三和弦的音，交给一般导和弦规则，不在此重写。新 arity 的 program 应**接上**既有 typed 规则（把 `leadingTriadNeighborRequirements` 等塞进 `chordToneNeighbors`/`avoidDoublings`），而非复制一份。
   - **同一规则勿两处并存**：规则迁到 typed requirement（落在被投影字段）后，勿在 per-progression 章节里再留一份等价软/硬约束——会重复报 finding。综合练习用 `inferredDissonanceConstraints(..., includeResolution=false)` 关掉重复的软解决，七音解决只由 `seventhResolutionNeighborRequirements` 拥有；独立七和弦章节保留逐槽表达（默认 `includeResolution=true`）。
   - 规则一律用不限 arity/转位 的 typed requirement 表达后，禁忌表探测器（见第 3 条）会自动把它投影到七和弦相邻对——`ii=>vii°43`、`I42=>IV` 等即随重刷进表。

3. **禁忌相邻进行数据化，勿写死**：某些两个和弦相邻在四部写作里永远写不出。这类「无解进行」**不写死成枚举里的启发式**，而是由求解器逐对探测、落到人类可读数据文件 `theory/src/jvmMain/resources/schoenberg/forbidden-transitions.txt`，运行时 `enumerate` 经 `SchoenbergForbiddenTransitions.isForbidden` 读取规避。
   - **用户报告“无解”必须深入诊断原进行，禁止只扩大搜索范围绕过**：先对用户所指的前排 / 指定进行逐条运行 trace，区分①符号目标预检拒绝、②typed 关系剪枝导致零候选、③四部硬规则剪枝、④节点预算耗尽；再把进行缩到最短相邻对或终止尾部复现，核对 enumerate 与求解器的硬 / 软规则语义是否一致。不能仅通过增大 `maxProgramAttempts`、beam / node budget、改排序，或继续向后搜索直到碰到另一条有解进行，就宣称问题已修复——这些最多是临时兜底。若原进行确实永远无解，进入禁忌表；若是软规则被硬投影、枚举 / 求解规则不一致或候选生成错误，应修根因并增加“原进行本身可解”的回归测试；若仅为预算耗尽，也须报告搜索深度与瓶颈后再有依据地调预算。
   - **探测器规则自动继承练习本身**：`SchoenbergForbiddenTransitionGeneratorTest` 不手列规则，而是从综合练习 `SchoenbergIntegratedTechTree.program(...)` 提取其全部 typed 规则族（完整性 / 重复避免 / 共同音 / 邻接预备解决）投影到相邻两槽。**只要按常规方式（typed requirement）新增/修改规则，重刷后即自动进入禁忌表**，无需改探测器。（软偏好类章节原则不进表——软规则不会导致「写不出」。）
   - **探测器只投影 typed 规则字段**（`chordToneNeighbors` / `toneCompleteness` / `avoidDoublings` / `adjacentCommonTones`），**不看原始 `constraints`**；且用的是 `progression=null` 的**开放域参考程序**。故想进禁忌表的规则必须①表达为 typed requirement 落在被投影字段，②在开放域（无具体进行）下就存在。per-progression 才生成的章节 raw `Constraint`（如 `inferredDissonanceConstraints`）在参考程序里为空，**进不了表**——`I42=>IV` 一度漏收即因七音解决只作为 per-progression 软 `Constraint` 存在（2026-07-21 改用 `seventhResolutionNeighborRequirements` typed 规则修复）。
   - **每新增 / 修改一类勋伯格规则后必须重刷禁忌表**：
     `./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests "*SchoenbergForbiddenTransitionGenerator*" -Pschoenberg.forbidden.write=true`
   - 禁忌表穷举不进入日常 `:theory:jvmTest`；需要校验已提交表是否过期时，显式运行
     `./gradlew.bat :theory:schoenbergForbiddenTransitionTest`。不带写开关时只校验，不改资源文件。
   - 因含跨和弦硬规则（导和弦五音、七音预备/解决等），表是**转位敏感**的：如 `vii°64 => iii6` 在表内、`vii°64 => iii`（根位）不在。同理 `ii => vii°43` 在表内（导七和弦五音 F 被 43 逼到低音、ii 低音≠F 无法同声部预备），而 `ii => vii°7`（根位，F 可在内声部预备）不在。七音解决同样转位敏感：`I42 => IV` 在表内（低音 7 必须下行到 6），`I42 => IV6`/`IV65`（低音已是 6）不在。判据与调性无关（C 大调探测一次）。

4. **根音 / 和弦选择规则必须让 enumerate 与求解器共享本体**（详见
   `docs/theory/schoenberg/root-chord-selection-rules.md`）：
   - 只依赖符号和弦序列的**硬规则**，须同时进入 enumerate 增量剪枝与运行时 `Constraint`；两侧委托同一
     policy / 纯判定，不得各写一套音级、转位或 arity 判断。
   - 只依赖符号和弦序列的**软偏好**，权重与公式只能定义在一个不可变 scoring policy 中。enumerate
     用它给前缀打分并按低分贪心展开，求解器用同一 policy 产生 `scoreDelta`；禁止枚举与求解分别维护权重常量。
   - 求解器可在共享和声分之外叠加声部进行、音域、排列等 realization 成本；这些声部成本不反向复制进
     enumerate。四部写作导致的「永远无解」仍走第 3 条禁忌表，不伪装成和弦选择偏好。
   - 测试必须证明：非法符号进行在两侧都被拒绝；同一合法进行的 enumerate 和求解器和声分完全相等；
     大 / 小调、同根音不同转位、三 / 七和弦均覆盖。

## 编码规范

**命名**：

| 类型 | 规范 | 示例 |
|------|------|------|
| 类/接口/文件 | PascalCase | `ScoreRenderer.kt` |
| 函数/属性 | camelCase | `analyzeHarmony()` |
| 常量 | UPPER_SNAKE | `MAX_VOICES` |
| 包名 | lowercase | `com.mecon.theory` |
| Compose UI | PascalCase | `@Composable fun ChordPanel()` |

**提交**（Conventional Commits）：

```
feat(theory): add chord voicing analysis
fix(renderer): correct beam direction for cross-staff
docs: update plugin development guide
refactor(core): extract immutable Score operations
```

## 文档规范

文档在 `docs/` 目录，分主题组织。

- 每篇 ≤300 行（开发或调优中的功能不必严格遵守；待功能点完全完善后再整理文档，满足300行约束）；索引与细节分离，同一概念不在多处重复
- 精简示例代码，优先用路径引用代码库
- 未实现内容以 🚧 标注，保留设计思路但与已实现部分明确区分
- **开发前**：通过文档定位相关文件；**开发后**：同步更新对应文档；修改数据模型时必须先更新 `docs/data_model/`

## 常用命令

```bash
.\gradlew.bat :apps:desktop:run    # 启动桌面应用
.\gradlew.bat build                # 构建所有模块
.\gradlew.bat test                 # 运行所有测试
.\gradlew.bat clean                # 清理构建输出
cd web
npm run prepare:engine             # 生成 Kotlin/JS npm payload
npm run dev:free-practice          # 启动自由练习 Web
npm run test:engine                # 重建引擎并运行 Web/跨端测试
```

## 开源协作约束

- 项目按根目录 `LICENSE` 的 GPLv3-or-later 发布；第三方代码、字体、图片和乐谱素材必须先核对并记录许可证。
- Commit 遵循 Conventional Commits：`type(scope): summary`，常用 type 为 `feat`、`fix`、`refactor`、`perf`、`test`、`docs`、`build`、`ci`、`chore`。详细 PR 要求见 [CONTRIBUTING.md](CONTRIBUTING.md)。
- PR 必须说明范围、验证命令、文档/CHANGELOG 影响和跨端/renderer 契约影响。若使用 vibe coding 或生成式 AI，必须附上原始 prompt、AI 工具及版本/模型、作者复核修改方式和第三方来源；不得只写“AI assisted”。
- 测试乐谱优先在测试中构造最小合成 `StorageScore`/`RuntimeScore`，不得提交未经授权的真实作品 XML。根目录 `logo.png` 是唯一项目 Logo 入口。
- `free-practice` Web 基线已完成并有跨端/浏览器门禁；无关任务不得顺手重排其源码、trace 或配套文档。
- 文档记录当前结论、未来 TODO 与少量弯路/注意事项；不要继续把每次提交的过程日志堆入架构文档。性能细节归 [docs/renderer/incremental-rendering.md](docs/renderer/incremental-rendering.md) 或 [docs/performance/large-score-editing.md](docs/performance/large-score-editing.md)。

测试乐谱：`test-scores/`（10 个分类样本）
依赖版本：`gradle/libs.versions.toml`
