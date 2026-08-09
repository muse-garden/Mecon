# 乐理分析与 AI 协作总体路线 🚧

> 本文是乐理引擎（theory）与大模型接入（MCP tools / skills）的综合设计与推进计划。
> 现状：theory 仅有 Chord/Scale 骨架 + ChordRecognizer；和弦分析插件端到端已通。
> 相关前置设计：[../theory/README.md](../theory/README.md) ·
> `todos/chord.md`（转调/功能分析）· `todos/polyphony.md`（缩谱/复调/动机）。

## 1. 核心矛盾与应对策略

| 矛盾 | 应对 |
|------|------|
| 教科书例子是四部和声，真实乐谱织体复杂 | **缩谱层**：用户 pick 主干 + 自动同度/八度重复识别，把总谱还原为可分析的单音声部组（polyphony.md 已有设计） |
| 乐理规则多、互相冲突、非死律（如莫扎特五度） | **软约束规则引擎**：规则不是布尔判断而是加权惩罚，冲突体现为不同风格预设下的权重差异，不需要"裁决" |
| 变化可枚举但优劣难评判 | **确定性搜索出 top-K + 解释，LLM 只做末端美学重排**；不在搜索内层调 LLM |
| LLM 无多模态、看不懂乐谱 | **分级文本表示**：概览 → 紧凑片段 → 缩谱视图，附带已算好的分析标注 |
| token 贵、小模型世界知识少 | **工具做计算，LLM 做选择**：乐理事实由内核给出并随数据附带，LLM 输入输出都是结构化的 |

## 2. 总体分层

```
┌─ Skill 层        场景化工作流（配和声 / 和声分析 / 声部检查 / 动机检索）
├─ MCP 工具层      读谱（分级文本）· 写谱（建议轨道）· 分析（调用内核）
├─ LLM 协作策略    意图→配置翻译 · top-K 美学重排 · 结果解释 · 生成剪枝谓词
├─ 表示层          乐谱 ↔ 紧凑文本（带 TimeCode/EventId 锚点，可回写）
└─ 确定性乐理内核  规则引擎 + 搜索/枚举 + 评分/解释（纯 Kotlin，theory 模块）
```

原则：**内核不依赖 LLM 即可独立使用**（面向用户的分析/检查功能直接消费内核）；
LLM 层是内核之上的增强，任何一环失效都能退化为纯确定性功能。

## 3. 确定性乐理内核

### 3.1 规则引擎：软约束 + 风格预设

```kotlin
// 设计草图
data class TheoryRule(
    val id: RuleId,                  // e.g. "parallel-fifths"
    val severity: Severity,          // HARD（剪枝） / SOFT（惩罚） / HINT（仅提示）
    val defaultWeight: Double,
    val check: (VoicingContext) -> List<RuleViolation>,
)

data class StylePreset(              // 巴洛克严格 / 教科书第 N 章 / 古典 / 浪漫 / 爵士…
    val id: String,
    val overrides: Map<RuleId, RuleConfig>,  // 权重覆盖、开关、例外条件（莫扎特五度）
)
```

- "前面禁止、后面有限允许"不需要特殊机制：同一规则在不同 preset 下 severity/weight 不同。
  教学场景可提供"课程进度"预设（只启用已学章节的规则）。
- 每条规则结果携带**位置锚点 + 人类可读解释**（"S-A 声部 m.3→4 平行五度"），
  这既是给用户的诊断，也是给 LLM 的低 token 语义输入。
- `RuleFinding` 不只表达错误，也表达正确写法的 `INDICATION`，例如终止式中导音 7-1
  解决。UI 与 LLM 消费同一份 finding，不从音符重新推理。
- 会缩小枚举范围的规则必须同时提供检查结果：生成时用于约束候选，用户作业检查时用于
  解释同一条规则是否满足。

### 3.2 搜索与枚举

- 场景：指定和弦的四部连接、给定旋律/低音配和声、给定 cantus firmus 写复调。
- 任务抽象见 [../theory/writing-engine.md](../theory/writing-engine.md)：`WritingTask`
  描述固定材料、目标、时间线与规则 profile；具体枚举交给 `CandidateSpace`。
- 算法：beam search / branch & bound。HARD 规则剪枝，SOFT 规则计入代价。
- 输出 **top-K 多样化候选**：加 diversity 惩罚（与已选候选的声部/和弦差异过小则降权），
  避免 K 个近似解——这直接回答"展示哪些变化供用户挑选"。
- "平稳连接还是加变化"不由引擎裁决：作为搜索的偏好参数（共同音保持权重、
  声部活跃度目标），由用户 UI 滑杆或 LLM 从自然语言意图翻译而来。
- 搜索内层必须局部检查。应用候选后只构造受影响的纵向 slice / `FixedVoiceTransition`
  / `TransitionContext`，调用局部规则；全谱扫描只用于用户作业批量检查和回归测试。

### 3.3 评分与解释

每个候选输出结构化的 `ScoreBreakdown`：总分 + 各规则惩罚明细 + 触发的风格特征
（"含那不勒斯六和弦""女高音级进下行"）。解释是一等公民——
用户 UI 与 LLM 消费同一份解释数据，LLM 无需从音符重新推理乐理事实。

## 4. 复杂织体 → 可分析输入

复调/和声分析的输入统一为**缩谱**（多个单音声部），不直接吃总谱：

- 缩谱创建、总谱↔缩谱多对多映射、八度位移记录：按 `todos/polyphony.md` 实施。
- 自动辅助：同度/八度重复旋律检测（先做完全重合检测，模糊重合后置）。
- 对 LLM 的意义：缩谱天然是低 token 的乐谱视图；MCP 读谱工具优先输出缩谱。

## 5. 乐谱的文本表示（供无多模态 LLM）

分级细节，按需取用：

1. **概览**（~100 token）：调性、拍号、小节数、轨道/乐器列表、已有分析标注的范围。
2. **紧凑片段**：按小节范围输出，行 = 声部，列对齐；音高用科学记谱 + 时值缩写。
   每小节带小节号锚点，事件可选携带 `EventId`，LLM 引用锚点即可精确回写。
3. **分析视图**：和弦标注、罗马数字、调性区域、已判定的和弦外音随片段附带——
   LLM 不必自己识别和弦，小模型因此也能工作。

格式定型前先在真实对话中试验 token 成本与引用准确率；候选参考 ABC/LilyPond
的紧凑性，但必须确定性、可逆、带锚点。

## 6. LLM 分工与接入时机

### 6.1 什么交给代码，什么交给 LLM

| 任务 | 归属 |
|------|------|
| 和弦识别、规则检查、搜索枚举、评分 | 确定性代码（快、可测、可回归） |
| 用户意图 → 搜索配置（preset 选择、权重调整） | LLM（小模型可胜任：输出是受限结构） |
| top-K 候选的美学重排与点评 | LLM（一次调用批量评 K 个候选） |
| 分析结果的自然语言讲解、教学问答 | LLM |
| 语义化剪枝（"避免过于学院派的进行"） | LLM **生成剪枝谓词**（受限 DSL/配置，注入搜索一次性生效），而非逐节点调用 |

### 6.2 三个接入时机

1. **事前**：自然语言 → `SearchConfig`（preset + 权重 + 目标描述）。桌面端的落地宿主
   是探索模式输入层：自然语言 → `CellRequest`（见 [../exploration/README.md](../exploration/README.md)）。
2. **事中（原则上避免）**：不在搜索循环内调 LLM——耗时与 token 用户不可接受。
   需要语义判断时改为事前生成谓词，或对中间层的少量代表性节点做一轮批量筛选。
3. **事后**：对 top-K（K≤10）做重排/点评/解释，附 `ScoreBreakdown` 而非原始音符。

### 6.3 token 经济与小模型适配

- 输入输出全部结构化；乐理事实（和弦性质、音程、违规）由工具预先算好随数据给出，
  小模型缺少的世界知识被内核补齐。
- 分级用模：意图翻译/格式转换用小模型；美学判断、教学讲解用大模型。
- Skill 内嵌少量 few-shot 示例固定输出格式；preset/规则说明按需通过
  `explain_rule` 工具拉取，不常驻上下文。

## 7. MCP 工具设计

### 7.1 原则

- **读写分离且写入受限**：分析类写入建议轨道 / PluginTrack / 缩谱，不直接改用户音符；
  改谱操作走与 UI 相同的 `commitNewState()`，可撤销。
- **分级读取**：先概览后片段，工具参数强制带范围，杜绝"一口气吐全谱"。
- **锚点贯穿**：所有输出带 TimeCode/小节号锚点，所有写入参数接受锚点。

### 7.2 工具清单（按批次）

**第一批（读 + 现有能力）**
`score_overview` · `read_measures(range, tracks, detail)` · `read_analysis(range)` ·
`recognize_chord(pitches | anchor)` · `list_presets` / `explain_rule`

**第二批（分析）**
`analyze_harmony(range, preset)`（罗马数字/功能，含转调区域） ·
`check_voice_leading(range, preset)` → 违规清单 ·
`annotate_chord / set_key_region`（写标注轨道）

**第三批（生成与搜索）**
`harmonize(melody_anchor, config, k)` → top-K + breakdown ·
`enumerate_next(context_anchor, config, k)` ·
`apply_candidate(candidate_id, target)`（写入建议轨道，用户确认后合入） ·
`find_motif(pattern | anchor, transforms)`

### 7.3 部署形态

- 桌面应用内嵌 MCP server（Kotlin JVM，先 stdio 或本地端口），暴露**当前打开的乐谱**，
  外部 agent（Claude Code 等）连接后与用户看同一份状态，写入实时反映在 UI。
- 另提供 headless 模式直接读写 `.mecon` 文件，供批处理/CI 场景。
- 插件系统已有 PluginTrack/序列化扩展点，MCP server 作为一个特殊"插件宿主"接入，
  复用 `ScoreStateManager` 的并发与撤销约束（见 threading.md）。

## 8. Skill 设计

每个 skill = 触发场景 + 工具编排 + 提示词模板，回答"这个 skill 适用什么场景"：

| Skill | 场景 | 编排要点 |
|-------|------|----------|
| `analyze` | "分析这段的和声/调性" | overview → read_analysis → 缺标注则 analyze_harmony → 讲解 |
| `harmonize` | "给这段旋律配和声" | 意图→config → harmonize → LLM 重排点评 → apply_candidate |
| `critique` | "检查我的四部和声作业" | check_voice_leading → 按课程 preset 过滤 → 讲解违规与改法 |
| `reduce` | "帮我把总谱缩成四部" | 自动重复检测 → 建议映射 → 用户确认 |
| `motif` | "这个动机在哪里出现过" | find_motif（移调/逆行/倒影变换）→ 汇总讲解 |

## 9. 推进步骤

| 里程碑 | 内容 | 依赖 |
|--------|------|------|
| **M0** | theory 补全：全部 ChordQuality 的 `toPitches()`、`Scale.fromMode`、`Key.diatonicChords`、音程/移调工具 | 无（roadmap 已列） |
| **M1** | 和声功能分析：罗马数字、功能标注、调性区域与转调（`todos/chord.md` §转调） | M0 |
| **M2** | 缩谱第一阶段：pick 总谱片段建缩谱 + 映射记录 + 完全重合检测（polyphony.md） | 无（与 M1 并行） |
| **M3** | 规则引擎：RuleFinding/RuleProfile + 局部 transition 检查 + 声部进行检查（确定性 critic），UI 展示违规与 indication | M0 |
| **M4** | 搜索引擎：WritingTask/CandidateSpace + 固定声部 target provider + 增量局部 finding 缓存 + 评分 + top-K 多样化 + ScoreBreakdown | M1 M3 |
| **M5** | 文本表示 + MCP 第一/二批工具 + 内嵌 server | M1（第二批需 M3） |
| **M6** | Skills + LLM 协作：意图翻译、top-K 重排、剪枝谓词 DSL；MCP 第三批 | M4 M5 |
| **M7** | 动机库与复调分析、临时和弦、模糊调性/PLR；复调需独立状态模型，不复用固定声部 frame（chord.md、polyphony.md 后续） | M2 M4 |

验证方式：
- M0–M4 用教科书习题做金标准回归（四部和声习题 + 巴赫众赞歌对照）；
  搜索结果的合法性可与教科书答案集自动比对。
- M5–M6 度量：单次任务 token 消耗、锚点引用错误率、小模型（Haiku 级）在
  `analyze`/`critique` skill 上的可用性。

## 10. 开放问题

- 紧凑文本格式的最终形态（自研 vs 基于 ABC 扩展）——M5 前用真实对话实验定型。
- 剪枝谓词 DSL 的表达力边界：从纯配置（权重覆盖）起步，避免过早引入代码执行。
- MCP server 与 Compose UI 的状态同步粒度（逐编辑推送 vs 请求时快照）。
- 多候选在谱面上的展示交互（并排小谱 vs 灰色叠加 vs 逐一试听），需 UI 原型验证。
- 规则注册与调度器：消费 `RuleApplicability` / `suggestedRuleSet`，让混合原位、转位、七和弦、外音的作业自动选择规则集。
- suppression 扩展：从成对 `RuleSuppression` 演进为基于 `RuleTag` / 规则层级的默认调解，避免章节增长后的平方级维护。
- 槽位调性上下文：把 `HarmonicState.key` 接入写作 provider，以支持离调与转调章节。
- staged solving：把配和声拆成“和声骨架 → voicing → 装饰/外音”等阶段，避免单次联合搜索爆炸。
