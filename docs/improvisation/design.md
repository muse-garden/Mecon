# 即兴模块设计方案 🚧

> 状态：**设计阶段，未实现**。概念背景见 [concept.md](concept.md)，里程碑见 [roadmap.md](roadmap.md)。
> 前置阅读：[../theory/constraint-program.md](../theory/constraint-program.md) ·
> [../theory/diverse-search.md](../theory/diverse-search.md) ·
> [../theory/solver-api.md](../theory/solver-api.md) ·
> [../theory/figuration.md](../theory/figuration.md) ·
> [../analysis/motive.md](../analysis/motive.md) ·
> [../audio/README.md](../audio/README.md)。

## 1. 核心判断：即兴模块是求解器的实时消费者

concept.md §九设想的架构——"乐理系统生成合法且可解释的候选，轻量模型负责排序"——与
已落地的 `ConstraintProgramSpec` → `ConstraintProgramSolver`（贪心 DFS 首解 + 多样化重启）
几乎一一对应。因此本模块**不新建生成算法**，只补四件事：

1. **意图词汇表**：两维意图（结构 × 表面）→ `ConstraintProgramSpec` 的编译；
2. **表面实现层**：四部骨架 → 织体音型（后续接 figuration 装饰阶段）；
3. **会话调度器**：前瞻计算、小节边界提交、无输入时的惯性延续；
4. **会话 UI**：候选卡片、张力/调性显示、桌面输入映射。

其余全部复用：候选搜索与 HARD 剪枝、`RuleFinding` 解释（直接作为卡片证据）、
多样性保证（diverse-search 的最小槽距离即 concept.md §九.3"只展示真正不同的选项"）、
乐谱数据模型、渲染与播放。

## 2. 分层架构与模块归属

```
用户输入（面板按钮 / 键盘 / MIDI 控制器 🚧I5）
    ↓ ImprovIntent = (StructuralIntent, SurfaceIntent)
IntentCompiler        意图 + ImprovState → ConstraintProgramSpec（1–2 小节窗口）
    ↓
SolverApi.solve       复用 :exploration → :theory ConstraintProgramSolver
    ↓ 3–5 个骨架候选（ChordVoicing 序列 + findings）
SurfaceRealizer       骨架 → 织体事件（确定性音型器；I2 起接 figuration Stage 2）
    ↓
SessionScheduler      前瞻缓冲 / 边界提交 / 惯性延续（§8）
    ↓
ImprovTimeline        StorageScore 增量 + 决策插件轨（§9）
    ↓
播放（AudioEngine）+ 渲染（现有管线）
```

```
:improv          会话引擎（KMP，无 UI）：ImprovState / IntentCatalog / IntentCompiler /
                 SurfaceRealizer / SessionScheduler / ImprovTimeline
                 依赖 :api :theory :exploration（spec 层类型与 SolverApi 在 :exploration）
apps/desktop     即兴面板 UI、播放接线、键盘输入映射
plugins（可选）  决策轨注释谱表沿 custom-track 三层配方实现
```

依赖方向与 [../ARCHITECTURE.md](../ARCHITECTURE.md) 一致：`desktop → improv → exploration → theory → api`。

## 3. 音乐状态（ImprovState）

会话引擎每次裁决时维护的不可变状态（对应 concept.md §九.1）：

```kotlin
data class ImprovState(
    val globalKey: Key,                    // 全局主调（会话开始设定）
    val localCenter: LocalCenter,          // 局部中心 + 置信状态（I3 前恒等于全局，§7）
    val position: SessionPosition,         // 小节 / 槽 / 乐句位置
    val currentFunction: HarmonicFunction, // 上一提交段末槽功能（T / S / D）
    val lastFrame: ChordVoicing,           // 上一段末纵向排布（衔接材料，§5）
    val activeSurface: SurfaceIntentId,    // 当前织体（惯性延续对象）
    val activeStructure: StructuralIntentId,
    val tension: Double,                   // 启发式张力估计（功能 + 不协和度 + 密度）
    val history: List<CommittedSegment>,   // 近期骨架签名（去重与动机接口用）
)
```

v1（I0/I1）不做局部调性追踪：`localCenter == globalKey` 恒成立，词汇表限定调内三/七和弦。

## 4. 两维意图词汇表（IntentCatalog）

意图是**数据条目**而非硬编码分支：`id`、显示文案 key、适用条件（当前功能 / 乐句位置的
谓词）、spec 模板、卡片特征提取参数。风格包（巴洛克 / 爵士）= 不同 catalog + 不同
`policyId` 词汇表，机制与 `RuleCatalog` / `Policies` 同构。

### 4.1 维度 A：结构意图（每 1–2 小节裁决一次）

v1 巴洛克自由前奏曲词汇表，与现有约束原语的映射：

| 意图 | 编译到 | 可用性 |
|------|--------|--------|
| 停留（延长当前功能） | `ChordAt`（同功能音级集）+ `AdjacentCommonTone` | ✅ 现有原语 |
| 进入前属 | `ChordAt`(IV / ii / ii⁶) + 章节连接规则集 `RuleSetAt` | ✅ |
| 半终止 | `ChordAt`(末槽 V) + 终止规则 `RuleAt` | ✅ |
| 正格终止 | 终止四六场景（`I⁶₄-V-I` 已接入 enumerate） | ✅ |
| 假终止 | `V7-VI` 阻碍进行（规则已注册） | ✅ |
| 五度序进 | 五度圈模进场景（`4-7-3-6-2-5-1` 已接入，四变体） | ✅ |
| 属持续音 | `PedalAt`（骨架阶段低音固定） | 🚧 依赖 figuration F4 |
| 主音化 V / 进入近关系调 | degree+alteration 词汇 + `KeyPlanSpec` | 🚧 依赖 M5 / P3 半音化 |

### 4.2 维度 B：表面意图（可即时切换，不触发重新求解）

| 意图 | 实现方式 | 可用性 |
|------|---------|--------|
| 柱式和弦 | 骨架直出 | ✅ |
| 分解和弦（上行 / 下行 / Alberti） | SurfaceRealizer 音型模板 | v1 新代码 |
| 密度 ±（每拍音数）/ 留白 | 音型模板参数 | v1 新代码 |
| 装饰高声部 / 经过音 | figuration Stage 2（p / n 弱位插入） | 🚧 依赖 F2 |
| 挂留链（4–3 / 7–6） | figuration Stage 2（s 强位变换）；过渡期可用 `ChordToneNeighbor` 在骨架层近似 | 🚧 依赖 F2/F3 |
| 增减声部 | realizer 声部数参数（骨架仍四部，表面取子集/加八度） | v1 新代码 |

两维分层的关键约束：**A 决定骨架（需要求解），B 决定表面（确定性变换）**——
这保证切换 B 无求解延迟，是实时性的结构基础（§8）。

## 5. 候选生成与衔接

每次结构裁决：

1. `IntentCompiler` 按意图模板生成 spec：窗口 1–2 小节（v1 固定映射：一槽 = 半小节，
   figuration F0 `MeterPlan` 落地后由和声节奏控制）；
2. **衔接**：上一段末槽 `lastFrame` 作为段首 fixed material（`PitchAt` / `MaterialConstraint.FixedPitch`，
   即 refine 的 pins 机制），使跨段 transition 规则（平行五度、共同音保持等）在段边界同样生效；
3. `SolverApi.solve` 走多样化搜索取 top-K（K≈8）；
4. **显示层聚类**：按骨架签名（degree / quality / position 序列）合并，展示 ≤5 张卡片——
   diverse-search 的最小槽距离已保证结构差异，此处只做去重，不重新聚类。

## 6. 候选卡片：确定性特征，不接语言模型

对应 concept.md §七，每张卡片固定展示以下由确定性特征生成的维度：

| 特征 | 来源 |
|------|------|
| 收束性（开放—收束） | 终止式规则的 INDICATION finding（求解已产出，直接复用） |
| 稳定度（稳定—不稳定） | 末槽功能 T/S/D + 不协和度 |
| 停留—推进 | 意图类别 + 和声节奏 |
| 调性距离（接近—远离主调） | 五度圈距离（I3 起，此前恒为 0） |
| 预计持续 | spec 窗口长度 |
| 密度变化（稀疏—密集） | 当前 vs 候选 realizer 参数差 |
| 动机连续性 | 🚧 I5，动机匹配层级 |

听感文案（"推进感增强，尚未真正结束"）由特征组合查表生成；`RuleFinding` 的
messageKey 提供理论解释行（"V/V → V"）。**不引入自由文本生成**。

## 7. 调性坐标与离调（I3）

concept.md §八的三套坐标挂在 `ImprovState.localCenter`：

- **状态机**：`CANDIDATE → TONICIZED → CONFIRMED`。进入条件 = 半音化词汇（副属等）
  被选择；TONICIZED 判据 = 局部中心持续 ≥N 槽；CONFIRMED 判据 = 局部终止式
  finding 出现。置信度由持续时长与终止证据合成。
- **三套坐标显示**：全局音级（♯4）、局部音级（7 of G）、和弦内角色（3rd of D7）——
  全部由 `Key` / `NaturalTriads` / `Chord` 现有查询导出，UI 层格式化。
- **双圈离调图**：外圈全局主调、内圈当前局部中心、路径 + 置信度文本。

依赖 theory M5（degree+alteration 目标身份）与 `KeyPlanSpec`；落地前 UI 只显示全局坐标。

## 8. 会话调度与实时性

### 8.1 两种运行模式

- **回合制（I0）**：无时钟。选择 → 求解 → 追加 → 播放试听。用于验证意图词汇表与
  卡片是否成立（"用户是否觉得自己在即兴"），不背实时性风险。
- **实时（I1）**：节拍时钟驱动（v1 内部 Clock，后接 audio transport）。

### 8.2 实时循环（双缓冲前瞻）

```
播放小节 N（已提交）
  ├─ N+1 已提交（上一轮裁决结果）
  └─ 后台为 N+2 预解：惯性意图（必解）+ 用户当前高亮的候选意图（优先）
提交点 = N+1 结束前 deadline（如提前一拍）
  ├─ 用户已选 → 提交所选候选
  └─ 未选 → 自动提交惯性意图首解（concept.md §六："保持当前行为"）
```

- 表面意图切换即时生效（下一拍起换音型），不等结构边界；
- 性能预算：1–2 小节窗口 + 词汇表收窄后，`ConstraintProgramSolver` 的节点预算机制
  （每目标 8–32 帧 + 节点上限）预计亚秒级；预算内跑不完 → 减少重启次数、少返回候选
  （沿 diverse-search "不足不补齐"原则），惯性意图首解永远优先保证；
- I0 期间建立求解耗时基准测试，作为 I1 的入场判据（见 roadmap）。

### 8.3 播放路径

- I0：整段重载现有 `Sequencer` 播放，可接受停顿；
- I1：需要**增量播放**。优先方案：直接经 `javax.sound.midi.MidiDevice` Receiver 按时钟
  调度已提交事件——这是 [../audio/README.md](../audio/README.md) §4.4 低延迟路径的
  第一个真实用例；fallback 为分段 Sequencer 无缝衔接。FluidSynth preset 预加载机制复用。

## 9. 会话落地与决策记录

- `ImprovTimeline` 增量构造标准 `StorageScore`：会话产物就是普通乐谱，结束后直接进入
  编辑器修改、保存、导出 MusicXML——即兴与创作管线打通。
- **决策轨** `mecon.improv.decision`（[../plugin/custom-track.md](../plugin/custom-track.md)
  三层配方）：每次裁决存 onset、structural/surface intentId、被选候选 fingerprint、
  备选摘要。注释谱表显示意图标签，支持复盘"音乐为什么在这里转向"。
- **会话日志**：spec + seed + 用户选择序列化保存 → 结果可复现（diverse-search 的
  seed 契约），同时为 I5 的个性化排序积累训练数据。

## 10. 输入与 UI（桌面优先）

concept.md §十一的平板方案与项目现状不符，调整为桌面面板优先：

- 布局：左列结构候选卡片（A）、右列表面候选（B）、中央已提交时间线（复用乐谱渲染）+
  张力条 + 调性显示；
- 键盘映射：左手 `Q W E A S D` → 结构候选 1–6，右手 `U I O J K L` → 表面候选 1–6，
  `Space` = 保持惯性，`Backspace` = 撤回上一段（回合制限定）；
- 快捷键经现有设置对话框（[../ui/settings.md](../ui/settings.md)）可配置；
- MIDI 键盘作为按钮阵列 → I5；3D 打印控制器不在本路线图。

## 11. 排序与个性化（I5，可选增强）

v1 排序 = 确定性评分（finding score + 惯性一致奖励 + 多样性顺位）。I5 起引入
concept.md §九.6 的轻量排序器：输入为符号特征（调性距离、声部移动、终止性、张力差、
动机相似度、密度变化、用户历史选择频率），输出选择概率，仅在合法候选间重排——
不参与生成。训练数据来自 §9 会话日志；冷启动用启发式权重。

## 12. 开放问题

- **变长窗口**：终止式类意图天然需要 2–3 槽，`SlotCountSpec` 变长 length 🚧
  （constraint-program §4）——v1 每个意图固定窗口长度绕过；
- **段首材料表达**：`lastFrame` 以 FixedPitch 注入后是否影响该槽的规则归因
  （finding 应算在前段还是本段），需要在 I0 实现时定契约；
- **张力估计**：v1 启发式（功能 + 不协和度 + 密度）是否足够支撑张力条 UI，I3 复评；
- **爵士节奏**：swing / groove 不是槽内音型能表达的，I4 需要 realizer 引入
  微观时值偏移层，与 `MeterPlan` 的关系届时定；
- **同一会话多次假终止/侧滑的重复感**：history 去重只看骨架签名，是否需要
  跨段的 `AllDifferent` 类软约束。
