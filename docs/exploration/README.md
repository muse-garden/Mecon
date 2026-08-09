# 探索模式（Exploration Mode）

> 本目录记录探索模式的目标架构与已落地范围。当前已实现首版原位三和弦写作交互：
> `:exploration` 请求模型/执行器、原位三和弦输出乐谱合成、桌面「探索」页中的规则树示例与进行练习。
> 自由练习工作台已可作为 `exploration.free-practice` module 随 `.mecon` 保存/打开；
> notebook 多乐谱文档、cell 增删移与内联 finding 高亮仍按 §6 后续推进。
>
> 相关前置：[../theory/writing-engine.md](../theory/writing-engine.md)（写作任务与求解器）·
> [../theory/rule-catalog.md](../theory/rule-catalog.md)（规则目录与规则关系）·
> [../ai/roadmap.md](../ai/roadmap.md)（LLM 协作总体路线）

## 1. 动机与定位

现有应用是标准乐谱编辑器：单文档、单乐谱、编辑为中心。乐理学习需要另一种交互模式：
用户围绕教材内容提出小问题（"C 大调 V→vi 用共同音保持连接是什么样""升 5 进行到 4 听起来如何"），
求解器给出可听、可看、可解释的乐谱答案。这类会话天然是 **notebook 形态**：
用户输入与求解器输出乐谱交错排列，构成一份可保存、可重跑的学习记录。

探索模式与编辑模式是**两种文档类型**，共享底层四层数据管线与渲染引擎：

| | 编辑模式（现有） | 探索模式（新增） |
|---|---|---|
| 文档 | 单一乐谱 | cell 列表，每个 cell 可含乐谱 |
| 乐谱数量 | 1 | N（材料谱可编辑，求解输出只读） |
| 中心交互 | 音符编辑 | 提问 → 运行 → 查看/试听/读解释 |
| 分析面板 | 右侧全局面板 | 选中 cell 下方内联展开 |

## 2. 核心概念

**探索文档（ExplorationDocument）**：有序 cell 列表，与 `.mecon` 乐谱文档同容器格式，
顶层 `kind: exploration` 判别。可保存、重开、重跑。

**Cell** 三类：

- `text`：说明文字 / 教材摘录。
- `score`：独立可编辑乐谱片段（自由材料、笔记性质的谱例）。
- `request`：结构化求解请求 + 可选的可编辑材料谱 + 附着的求解输出（Jupyter 式
  "输入 cell + 挂在其下的输出"，输出不是独立 cell）。

**输出与过期**：输出随文档持久化（重开不必重跑）。请求或材料变化后输出标记"已过期"，
用户手动点"运行"重跑（不自动重跑）。

**只读但可交互**：求解输出乐谱不可编辑，但支持点击选中、finding 高亮、播放。

**结构化请求优先，LLM 后置**：`CellRequest` 是可序列化的受限协议，首版由表单式 UI 生成；
它同时就是未来 LLM 自然语言输入层的目标格式（ai/roadmap §6.2 "事前"环节），本期只定义协议。

## 3. 探索的三个层级

### 层级 1：具体规则的示例

用户先从**规则目录**中选择连接模式/规则，UI 再按 `RuleCatalog` 的输入 spec 自动给出调性、常用和弦对、
伴随规则与错误示例开关；求解器给出满足所选规则的连接示例。要点：

- 规则需拆分到可单选粒度，并声明相互关系（从属 / 互斥 / 可共选），见
  [../theory/rule-catalog.md](../theory/rule-catalog.md)。例如根音四（五）度关系下辖三种
  互斥连接模式，内声部导音跳进从属其下；升 5→4 禁则从属于根音二（七）度关系。
- **错误示例**：用户可要求"演示违反某条规则"（如小调 V→VI 中升 5 进行到 4），
  求解器在其余规则照常约束下强制该违规出现，供试听对比。机制见 rule-catalog.md §5。

### 层级 2：章节内容的练习

用户指定一条和声进行（音级序列），求解器按当前章节约束 preset 写出完整四部连接。
可对单个和弦附加**排列偏好**（开放 / 密集），求解器尽量遵守：偏好编译为高权重软约束，
无法满足时仍给出最优解并以 finding 解释偏离。现有原位、转位三和弦与七和弦门面
均编译为 `ConstraintProgram`。

### 层级 3：多章节、多目标的练习 🚧 初步设计

跨章节的综合任务（如"给旋律配和声，先选和声骨架再写 voicing"）。方向：

- 请求编译为 `WritingTaskPlan` 多阶段流水线，前一阶段输出作为后一阶段固定材料。
- cell 间引用：`MaterialRef(cellId, candidateIndex)` 让下游 cell 消费上游选定的候选；
  上游重跑后下游级联过期。
- 跨章节 `RuleProfile` 合并（"课程进度"预设：启用已学章节规则的并集，后学章节可覆盖严重度）。

细节待层级 1/2 落地后补充。

## 4. 与编辑模式的融合 🚧 远期

分析真实作品时：在编辑模式选中段落 → "在探索模式中打开" → 新建探索文档，
首 cell 为导入材料（v1 复制内容并记录来源文件 + TimeRange 锚点；双向实时联动后置）。
新建乐谱对话框同时提供"新建探索文档"入口。

## 5. 模块划分

```
exploration/                KMP 模块：文档模型、CellRequest、请求编译器、
                            输出乐谱合成、fingerprint（依赖 :api :theory）
theory/                     RuleCatalog、RuleRequirement、演示模式（见 rule-catalog.md）
apps/desktop/ui/exploration/  ExplorationView、cell 组件、内联分析面板
apps/desktop/service/       ExplorationSession、per-cell 乐谱宿主
```

依赖方向不变：`apps/desktop → exploration → theory → api`。
`:exploration` 不依赖 `:core` / `:renderer`；compute 与渲染在 desktop 层按现有管线执行。

勋伯格请求的章节特例由 theory descriptor 声明：必选/允许的和弦解释选择、是否允许和弦筛选、
以及枚举进行是否需要穷举。通用 `ExplorationRequestRunner` 只按请求类型分发；
`SchoenbergExplorationRequestRunner` 执行这些能力声明，再由 theory 的 exercise handler 选择章节，
不在 exploration 按副属、减七、终止式等 exercise id 分支。乐谱装配另置
`ExplorationScoreAssembler`，请求编排不再同时承载各类和弦的存储层构造。

勋伯格变化音选择统一使用 `selections: Map<String, List<String>>`；桌面状态、solve 请求与
enumerate 请求都直接传 selection key，不再保留按和弦族扩展的专用字段或归一化桥接。新章节只增加
curriculum selection definition 与 handler，不扩展 exploration 请求字段。
和弦筛选及 constraint-program selector 可按实际音高集合、音响 identity 或解释 identity 定位。

请求执行按 `RuleExampleRequestRunner`、`ProgressionRequestRunner`、
`SchoenbergExplorationRequestRunner`、`ModulationExplorationRequestRunner` 分离；顶层
`ExplorationRequestRunner` 只做 sealed request 分发与统一诊断。四部谱面统一适配成
`FourPartVoicingFrame` 后由一条 SATB 装配管线生成。
RuleExample 的规则要求、场景选择及三/七和弦槽位由 `RuleExampleSemantics` 编译一次，
spec 编译路径与直接执行路径共同消费，避免两套语义漂移。

桌面输入面板的组件边界使用 `ExplorationInputState/ExplorationInputActions`，并按规则示例、
进行、勋伯格练习、转调工具和运行控制拆成子状态/动作。转调模式使用五度圈与公共和弦列表
完成“调 → 和弦”和“和弦 → 调”双向筛选，协议与规则见
[../theory/modulation.md](../theory/modulation.md)。`ExplorationView` 仍拥有 Compose 可变状态，
编辑器组件只读取不可变快照并上报动作，避免模式字段和回调沿组件树逐项透传。

### 5.1 自由练习编辑上下文

自由练习使用独立的 `HarmonyPracticeScoreHost`，并将它提升为探索页当前的
`EditableScoreHost`。顶层工具栏和全局撤销/重做快捷键在探索页只路由到该宿主；
主乐谱仍使用 `ScoreSession`，两套历史栈及 `canUndo/canRedo` 状态互不影响。五线谱
预览内部不再放置第二套撤销/重做按钮。

自由练习五线谱使用公共 `HorizontalScoreEditor`：选择、框选、音符功能与音符编辑按钮都在
谱面上方横向排列，宽度不足时自动换行；二全音符、六十四分音符等边缘时值和演奏法默认
折叠；功能组不显示名称，以竖向分隔线区分。旧“编辑通道”及缩谱式谱表行首选择器均已删除，
改用与主界面音符调板完全相同的 1–4 工具栏声部编号按钮。编号表示每个谱表内部的 voice number：
鼠标所在 staff 决定谱表，按钮决定该谱表内的声部；这里没有跨整谱“活动声部”、灰显或选区
过滤。时值、附点、升降号、连音线、连音符、符杠、演奏法与声部修改统一委托
`ScoreSelectionEditor + EditableNoteHost`；主页面和自由练习不得各自实现选区投影或切换语义。
编辑声部与后台分析声部分离。钢琴卷轴另有“全部”输入目标；该模式通过
`AutomaticVoiceAssigner` 为新音及既有自动音重新配声，手动指定和用户交换过的事件保持固定。
五线谱与钢琴卷轴共享 `EventSection` 选区；卷轴选中事件后点击另一声部，会与目标声部同
onset 的事件做原子交换。

自由练习工作台将五线谱预览和钢琴卷轴置于同一可拖动分隔条的左右两侧；比例限制在
25%–75%。任一面板可折叠，折叠条保留“展开”操作，且不会改变两视图共享的选区和编辑状态。

## 6. 里程碑

| 里程碑 | 内容 | 文档 |
|--------|------|------|
| **E0** ✅ | theory：RuleCatalog + 规则关系 + `RuleRequirement`（要求出现/要求违反）+ 原位三和弦规则组补全 | [rule-catalog](../theory/rule-catalog.md) |
| **E1** ✅ | `:exploration` 模块：文档模型 + 序列化 + fingerprint + 请求编译器（层级 1/2）+ 输出乐谱合成 | [document-model](document-model.md) |
| **E2** | 多乐谱状态宿主：`ActiveScoreContext` 重构、Editable/Readonly host、per-cell 渲染管线 | [ui-interaction](ui-interaction.md) §4 |
| **E3** ◐ | 桌面 ExplorationView 已提供单 request cell、规则树输入、运行/过期、候选切换；自由练习 module 已接 `.mecon` 打开/保存与自动导航，notebook cell 增删移及文档持久化未做 | [ui-interaction](ui-interaction.md) |
| **E4** ◐ | 层级 1 端到端已按规则输入 spec 自动调整输入并演示可开放的违规；只读谱点击、finding 高亮联动与候选内播放已做；完整 cell 化与持久化未做 | 同上 |
| **E5** ◐ | 层级 2 进行练习 cell 已可输入音级序列；排列偏好约束未做 | [document-model](document-model.md) §5 |
| **E6** 🚧 | LLM 输入层（自然语言→CellRequest）、层级 3、编辑模式融合 | [../ai/roadmap.md](../ai/roadmap.md) |

验证：E0/E1 以教材习题做金标准回归（求解结果不含 HARD finding、要求的 pattern indication
必出现）；E4 起增加 UI 手测清单（过期传播、焦点切换、高亮联动）。

## 7. 文档索引

| 文档 | 内容 |
|------|------|
| [document-model.md](document-model.md) | 文档/cell/请求数据模型、序列化、过期指纹、请求编译与求解执行 |
| [ui-interaction.md](ui-interaction.md) | Notebook UI、焦点与选中模型、内联分析面板、多乐谱状态架构 |
| [free-practice-workbench-interaction-v2.md](free-practice-workbench-interaction-v2.md) | ✅ 自由练习两栏工作台、统一时间投影、双谱表自由声部与分析声部分离实施基线 |
| [free-practice-auto-writing.md](free-practice-auto-writing.md) | ✅ 自动声部写作、回溯/重写/换结果、工具栏设置、撤销与回放；锁定/refine 后续 |
| [free-practice-customary-progressions-plan.md](free-practice-customary-progressions-plan.md) | ✅ 惯用进行扩充、章节复用、重叠模型、所选和弦交互与自动写作修复实施记录 |
| [free-practice-multiplatform.md](free-practice-multiplatform.md) | ✅ 自由练习共享 session、Web 首发与移动复用边界 |
| [free-practice-extension-guide.md](free-practice-extension-guide.md) | 当前功能调整、新 intent/投影/后台能力及跨端验收操作手册 |
| [free-practice-web-workbench-completion-plan.md](free-practice-web-workbench-completion-plan.md) | ✅ 公共 Web 乐谱编辑器、和声时间轴、右侧面板与收口门禁实施记录（钢琴卷轴后置） |
| [free-practice-web-desktop-parity-plan.md](free-practice-web-desktop-parity-plan.md) | ✅ 共享时间轴 raw scene/interaction、谱面布局与双工具栏桌面统一 |
| [free-practice-web-editor-capabilities.md](free-practice-web-editor-capabilities.md) | Web 五线谱与自由练习工作台逐项能力矩阵 |
| [free-practice-web-review-2026-08.md](free-practice-web-review-2026-08.md) | Web 工作台评审发现的性能热路径、门禁空跑与边界重复问题及修复顺序 |
| [scripting.md](scripting.md) | 🚧 脚本引擎（GraalJS）：沙箱、脚本 API、ScriptedRequest cell 集成 |
| [../theory/rule-catalog.md](../theory/rule-catalog.md) | 规则目录、层级与互斥关系、错误示例机制 |
| [../theory/solver-api.md](../theory/solver-api.md) | 🚧 求解器 API：五入口协议、能力清单、表单渲染、里程碑 |
| [../theory/rule-scenes.md](../theory/rule-scenes.md) | 🚧 规则适用场景模型与符号级枚举 |
| [../theory/constraint-program.md](../theory/constraint-program.md) | 🚧 约束程序 DSL、LinePattern、refine 语义 |
