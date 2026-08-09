# 分层分析与创作路径（Layered Analysis）🚧 设计

> 状态：**A0/A1/A5 与 orchestration O0/O1/O2 基础已落地**。源自 [todos/polyphony.md](../../todos/polyphony.md) 的复调分析需求，
> 回答两个问题：**复杂总谱如何分层分析**；**同一套架构如何辅助创作**。
>
> 前置/参考：[../exploration/README.md](../exploration/README.md)（notebook 实验形态、多乐谱宿主）·
> [../theory/figuration.md](../theory/figuration.md)（外音还原管线）·
> [../plugin/custom-track.md](../plugin/custom-track.md)（标注轨与着色）

## 1. 动机

音乐分析天然分层：大层级（整体曲式、调性布局）、中层级（和弦走向、动机使用）、
小层级（动机装饰、和弦外音、不同乐器的八度重复）。各层级适合不同分析形态——
大层级直接在总谱上画，中小层级需要提取片段单独分析。

创作是分析的逆过程，且希望**保留完整创作路径**：写总谱时对照已写出的和弦进行；
创作好缩谱后，能方便地更换每段旋律的配器并试听。因此分析产物不是一次性缓存，
而是与作品共同持久化的**层级栈**，创作与分析共享同一套数据。

## 2. 核心抽象：层级栈与映射通道

```
标注层   曲式分段 · 调性布局 · 和弦 · 动机出现 · 外音分类
  │        （插件标注轨，锚点 EventId，可挂在下面任意一层的谱上）
  ▼
表面层   总谱（配器后的实际乐谱，现有编辑文档）
  ↕  NoteLink + 演奏者/谱表分配（orchestration.md：线 → 演奏者 → 谱表两张时变映射）
内容线   与乐器无关的单声部线（与主谱同时间轴；tutti = 一线多人演奏）
  ↕  NoteMapping：多对多音符链接 + 八度位移（reduction.md）
缩谱层   单音声部还原谱（可多份、时间段可重叠、互相独立）
  ↕  FigurationAnalysis.reduce（figuration.md §6：去外音）
骨架层   和声骨架 + 和弦序列（figuration Stage 1 的输出形态）
```

- **分析 = 自上而下还原**：总谱 → 缩谱（合并齐奏/八度重复、拆分复音）→ 骨架（分类外音）
  → 功能序列 → 终止式/曲式。
- **创作 = 自下而上实现**：曲式计划 → 和声进行 → 缩谱旋律 → 配器成总谱。
- **NoteMapping 是层间投影通道**：锚在缩谱音符上的标注（动机出现、外音类型、finding）
  经链接重锚到总谱音符显示；反之总谱选区可投影到缩谱定位。
- 每一层都是持久数据，修改任一层不销毁其他层；层间偏离**可见而非强制同步**
  （偏离标记是信息，不是错误）。

## 3. 三个层级对应的分析形态

| 层级 | 对象 | 分析形态 | 数据载体 | 文档 |
|------|------|---------|---------|------|
| 大 | 曲式、调性布局 | 直接在总谱上画（选区 → 标记） | `mecon.form` 插件轨 span 事件 + 注释谱表 | [form.md](form.md) |
| 中 | 和弦走向、动机 | 提取缩谱分析，经映射投影回总谱 | 缩谱 + 和弦轨 + `mecon.motive` 轨 | [reduction.md](reduction.md) · [motive.md](motive.md) |
| 小 | 外音、装饰、八度重复 | 表面 ↔ 缩谱的映射本身 + 外音分类 | `StorageNoteLink`（octaveShift、偏离态）+ `NonChordToneClassifier` | [reduction.md](reduction.md) §7 |

## 4. 与现有模块的关系

- **exploration（实验形态） vs 本目录（归档形态）**：探索文档回答"这个片段单独拿出来是什么"
  的即时问题（cell、手动重跑、候选对比）；分层分析的定稿标注与缩谱**存进作品文件**，
  随作品长期维护。二者接缝：选中缩谱/选区 → "在探索模式中打开"（exploration README §4），
  `MaterialRef` 增加来源锚形态 🚧。
- **figuration**：缩谱处理**配器维度**的还原（谁在演奏 → 单声部线条），figuration 处理
  **装饰维度**的还原（线条 → 和弦音骨架）。两级串联构成完整分析管线；缩谱需满足的
  "全部单音声部"条件正是 `FixedVoiceScore` 的现有校验。
- **插件标注轨**：和弦轨（`mecon.chord_analysis`）已实现；曲式/调性/动机轨沿用同一
  三层事件 + `AnnotationStaffProvider` + `NoteStyleProvider` 配方，新增 span 类注释元素。
- **多乐谱宿主（exploration E2）**：缩谱编辑与总谱-缩谱对照视图复用 `ActiveScoreContext`
  重构与 per-score 渲染管线；区别在撤销模型——缩谱是主文档的嵌套字段，走**同一撤销栈**
  （lens 式提交，见 reduction.md §6），而非探索模式的 per-cell 独立栈。
- **写作引擎**：缩谱声部可作为 `WritingTask.fixedMaterial`（给旋律配和声）；
  `WritingTaskPlan` 的多阶段流水线是本层级栈的"机器求解版"，人工创作与求解共享层级。

## 5. 模块划分

```
api/            StorageReduction / StorageNoteLink / NoteRef（storage 层新顶层字段）
                StorageOrchestration（players / lines / performances / staffAssignments）
theory/         MotiveShape / MotiveMatcher / 临时和弦检测 / FigurationAnalysis（已列 figuration）
plugins/analysis-*  曲式轨、调性轨、动机轨的注册、注释谱表 provider、着色 provider
apps/desktop/   缩谱面板、映射交互、对照视图、一致性着色接线
```

依赖方向不变：`apps/desktop → plugins → theory → api`。不新增 KMP 模块；
若映射一致性计算出现第二个消费方，再考虑抽 `:analysis`。

## 6. 里程碑

| 里程碑 | 内容 | 依赖 |
|--------|------|------|
| **A0** | 缩谱 + 链接数据模型、序列化、一致性检查与偏离着色 | — |
| **A1** | 提取交互：创建缩谱、选段映射（复音三策略）、重合检测建议多对一 | A0 |
| **A2** | 和弦接入缩谱：和弦轨挂缩谱、和弦音/外音标记、临时和弦检测 | A0 · figuration F1 |
| **A3** | 动机库 v1：旋律动机、变换与相似度分层匹配、动机轨、映射投影 | A0 |
| **A4** | 大层级标注：span 注释元素、曲式/调性轨、终止式辅助检测 | figuration F1（检测部分） |
| **A5** | 创作方向：实现操作（缩谱→总谱）、更换配器、对照视图、UNREALIZED 待办 | A0 · A1 |
| **A6** 🚧 | 配器变体试听、intentional 偏离、跨作品动机库、探索模式深度融合 | A3 · A5 |
| **O0-O5** | 配器与谱表分配（演奏者/内容线/时变分配、标签、播放路由、分谱导出），详表见 [orchestration.md](orchestration.md) §10 | 与 A0 共用 NoteLink 基建 |

验证基线：A0/A1 以真实总谱片段（test-scores 扩充交响片段）做映射-偏离金标准；
A2/A3 以教材谱例（外音分类、命运/Mahler 动机变形）做回归；A5 增加 UI 手测清单
（映射着色联动、配器替换后链接保持、播放对照）。

## 7. 当前可用闭环

- 顶部工具栏“分析/创作”页可创建不带映射的空缩谱；映射在任一侧选材后动态建立。
- “绑定/配器”支持 `缩谱 → 内容线 → 演奏者 → 总谱` 与反向的
  `总谱 → 演奏者 → 内容线 → 缩谱`；两者都建立同一组两段 NoteLink。
- A0 一致性报告给出 `OK / PITCH_DIVERGED / TIME_DIVERGED / DANGLING`，并统计 `UNMAPPED / UNREALIZED`。
- “视图”页的“分屏对照”会把当前缩谱作为右侧可编辑谱面，主谱与缩谱共用同一撤销栈并维护明确链接的双向同步。
- 新建乐谱可直接设置独奏/合奏、演奏人数与谱表分配；已有乐谱可在“分析/创作”页打开“演奏者/谱表”。详见 [interaction-v1.md](interaction-v1.md)。
- 旧 `StorageInstrument` 可迁移为 player；默认谱表分配与每条内容线的演奏者/谱表/voice 路由均可编辑。

## 8. 文档索引

| 文档 | 内容 |
|------|------|
| [reduction.md](reduction.md) | 缩谱与音符映射：数据模型、时间对齐、一致性/偏离、提取交互、和弦与外音接入 |
| [motive.md](motive.md) | 动机库：动机类型、规范形、变换与相似度分层、检测与标注 |
| [form.md](form.md) | 大层级标注：曲式分段、调性布局、span 渲染、辅助检测 |
| [composition.md](composition.md) | 创作路径：对照创作、缩谱→总谱实现、更换配器与试听、求解器接缝 |
| [orchestration.md](orchestration.md) | 配器与谱表分配：演奏者/内容线/两张时变映射、tutti-divisi 合并记谱、标签生成、播放路由、分谱导出 |
| [fragments-interaction-proposal.md](fragments-interaction-proposal.md) | 待审阅：缩谱多层级、直接编辑与后续片段素材台 |
