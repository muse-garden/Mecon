# 神经写作模型：规则求解器 × 神经网络混合架构 🚧

> 状态：设计阶段。前置：[roadmap.md](roadmap.md)（LLM 协作总路线）·
> [../theory/free-practice-window-voicing.md](../theory/free-practice-window-voicing.md)（窗口写作）·
> [../theory/figuration.md](../theory/figuration.md)（和弦外音两阶段）·
> [../analysis/motive.md](../analysis/motive.md)（动机库）。
> 本文回答：在现有勋伯格规则体系与自由练习写作器之上，如何以**个人开发者可控的成本**
> 训练一个神经写作模型，补齐规则难以量化的部分（外音选择、旋律性、后续复调），
> 且最终模型在消费级显卡（甚至 CPU）上本地运行。

## 1. 定位与分工

### 1.1 现有能力盘点与缺口

| 能力 | 现状 | 缺口 |
|------|------|------|
| 和声骨架（和弦选择 + 四部 voicing） | ✅ `ConstraintProgramSolver` / `FreePracticeWindowVoicer`，硬规则可证、可解释 | 无 |
| 和弦外音 | F1 分析路径 ✅；生成靠 `FigurationOp` 枚举（F2/F3 🚧） | **"用哪种外音、放在哪"是审美问题**，密度预算 + 判定器只保证合法，不保证好听 |
| 旋律写作 | 教材旋律规则（禁则/软偏好） | "什么旋律好听"无法穷举成固定指标 |
| 复调 / 织体 | species 设计已挂 F2 后 🚧 | 线条间的呼应、节奏互补更是审美问题 |

结论：**规则体系的边界恰好是"合法 ≠ 好"的地方**。神经网络的角色是提供
*风格先验*（在真实作品分布上学到的"作曲家会怎么写"），而不是替代规则。

### 1.2 混合架构：generator–critic–repairer

```
用户控制（和弦进行 / 固定声部 / 动机 / 密度旋钮）
        │ 编译为条件序列（§4）
        ▼
┌─ 神经生成器 ──┐  采样 k 个表面候选（装饰化乐句 / 旋律 / 对位声部）
└───────┬───────┘
        ▼
┌─ 规则 critic ─┐  现有内核：硬规则 finding、NonChordToneClassifier 还原、
└───────┬───────┘  条件符合度（reduce 后和弦骨架 = 用户给定进行？）
        ▼
┌─ 写作器修复 ──┐  违例局部重解（窗口写作器以违例槽为窗口、模型输出为 baseline）
└───────┬───────┘  修不动 → 丢弃该候选
        ▼
top-K 候选 + ScoreBreakdown/finding → UI（与现有候选会话同一协议）
```

- **内核不依赖模型**（roadmap §2 原则不变）：模型缺席时退化为纯写作器路径。
- 与 figuration 两阶段的关系：Stage 1（骨架）仍归写作器——它可证、可控、已完成；
  神经模型主攻 **Stage 2 及以后**（装饰、旋律化、复调声部），即 `FigurationCandidateSpace`
  的"审美增强替代"。骨架可判定型外音（延留链等）的反向投影机制照旧。
- **明确不做**：整体曲式结构（用户负责乐句以上的组织）、音频/成品输出（Suno 类）、
  云端推理依赖。

## 2. 相关研究与可复用资产

| 类别 | 工作 | 与本项目的关系 |
|------|------|----------------|
| 乐谱级基模 | **[NotaGen](https://github.com/ElectricAlexis/NotaGen)**（MIT，110M/244M/516M，1.6M 谱 ABC 预训练 + 9K 古典乐谱微调 + CLaMP-DPO RL） | **首选基模**：古典乐谱域、含微调与 DPO 脚本、24GB 单卡可微调 |
| 演奏级基模 | [Anticipatory Music Transformer](https://github.com/jthickstun/anticipation)（Apache-2.0，~128M/360M/780M，Lakh MIDI） | infilling/伴奏控制的原生实现；但为演奏 MIDI，缺声部/拼写语义（§3） |
| 控制方法 | [FIGARO](https://arxiv.org/abs/2201.10936)（描述→序列重建学习） | 条件化训练目标的直接模板：**用确定性特征提取器自动造 (描述, 表面) 对** |
| 控制方法 | MuseCoco（属性 token 前缀） | 密度/音域等标量旋钮的编码方式 |
| RL | NotaGen 的 CLaMP-DPO（无人工标注偏好对） | 审美奖励可选件；DPO 管线可直接复用 |
| RL | [SMART](https://arxiv.org/pdf/2504.16839)（GRPO + 审美奖励） | **教训**：过优化导致多样性坍缩 → §6.2 的正则与监控 |
| RL | RL-Tuner / [RL-Duet](https://arxiv.org/pdf/2002.03082)（规则奖励） | 规则做奖励的先例；本项目奖励器远比其规则集完备 |
| 巴赫路线 | Coconet / DeepBach（众赞歌 infilling） | N1 先导实验的规模参照（~10M 参数即可工作） |
| 数据 | [PDMX](https://arxiv.org/abs/2409.10831)（250K+ 公有领域 MusicXML，含质量元数据） | 主语料；MusicXML 语义完整，无版权风险 |
| 数据 | OpenScore Lieder / String Quartets、KernScores/CCARH、DCML annotated corpora、371 巴赫众赞歌 | 高质量古典子集；DCML 自带和声标注可校验自动标注器 |

## 3. 基模选型决策

**核心分叉：乐谱级（ABC/MusicXML）还是演奏级（MIDI event）？——选乐谱级。**

| 维度 | 乐谱级（NotaGen 系） | 演奏级（anticipation 系） |
|------|---------------------|--------------------------|
| 声部归属 | ABC voice 显式存在 | 无声部概念，需事后拆分（不可靠） |
| 音高拼写 | 显式（外音分类依赖 F♯ vs G♭） | MIDI 音号，需重推拼写 |
| 与 Mecon 往返 | MusicXML↔StorageScore 已有互操作 | 量化/转写损失恰是本项目核心信息 |
| infilling 控制 | 需自行设计训练目标（§4.3） | 原生支持 |
| 预训练域 | 1.6M 乐谱 + 古典微调，风格对口 | Lakh（流行 MIDI 为主） |

判定性理由：本项目的 critic 与修复器都吃**精确记谱**（外音判定器需要拼写与拍位，
规则检查需要声部），演奏级表示在进入 critic 前就把关键信息丢了。infilling 能力可以
通过训练目标补造（span-infilling 是标准做法），而拼写/声部语义无法从 MIDI 补回。

**决策**：
1. 主线：**NotaGen-small（110M）起步，效果不足升 medium（244M）**，在其权重上做
   条件化继续训练（§4）。license MIT，含微调/DPO 脚本。
2. 先导（去风险）：在 371 众赞歌 + KernScores 四部子集上**从头训 ~10M 参数微型模型**，
   跑通"数据→条件 SFT→规则 DPO→集成"整条管线后再上基模——管线错误在小模型上
   数分钟一轮迭代，比在 110M 上试错便宜两个量级。
3. anticipation 不作为主线，但其 interleaved 控制序列思想进入 §4.3 的 infilling 设计。

## 4. 表示与控制接口（核心设计）

### 4.1 序列表示

沿用 NotaGen 的 **interleaved ABC + bar-stream patch** 表示（不改词表、不改架构，
最大化保留预训练权重价值）。新增内容全部放在**控制前缀段**与 ABC 注释行内，
对基模是普通字符流，靠继续训练学会其语义。

`StorageScore ↔ interleaved ABC` codec 落在 theory/io：导出（训练数据 + 推理条件）与
回读（模型输出 → StorageScore → critic/物化）双向，**往返保真测试是 N0 判据**
（声部、拼写、tie、tuplet；不保真的元素显式列入不支持清单）。

### 4.2 控制词汇（条件前缀）

全部条件都是**可由 Mecon 内核从任意乐谱确定性提取**的——这是 FIGARO 式训练的前提，
也是本项目独有优势：训练标注不靠人工，靠已有分析内核。

```
%%key C major            调性（含转调点：%%key@m.9 G major）
%%meter 4/4
%%harmony m.1 I | V6 | I | IV V | I    逐小节和声骨架（罗马数字+转位，
                                        内核 reduce/分析路径产出）
%%fig-density 0.4        外音密度（每拍外音数归一化；对齐 FigurationDensity）
%%fig-types p n s        允许/期望的外音类型集
%%motive M1 m.1:S m.3:A  动机标签与出现位置（来自动机库规范形 id）
%%fixed S                infilling：哪些声部/小节是给定材料（§4.3）
```

- 和声骨架是**最重要的控制**：用户在自由练习工作区选好的和弦进行直接编译成
  `%%harmony`，模型负责"把这条进行写得好听"。
- 每个条件独立可缺省；训练时按比例随机丢弃条件（condition dropout），
  使推理时任意子集组合都在分布内，同时天然获得 classifier-free guidance 的调节手段。

### 4.3 训练目标

1. **描述→表面重建**（FIGARO 式）：从语料每个乐句提取条件前缀，训练还原原表面。
2. **span infilling**：随机遮蔽若干（声部 × 小节区间），被遮蔽内容以占位符标记、
   完整内容移到序列尾部生成——用户"锁定已写声部/pin 音符，补其余"的直接对应。
   两目标混合训练（比例 ~7:3，N2 调参）。

### 4.4 推理时的用户控制面

| 用户操作 | 编译为 |
|----------|--------|
| 工作区和弦进行 | `%%harmony`（`WorkspaceChordTargetCodec` 已有目标编码可复用） |
| 锁定声部 / `VoicePitchPin` | `%%fixed` + infilling mask |
| "多一点经过音 / 只要延留" | `%%fig-density` / `%%fig-types` |
| 动机应用 | `%%motive`（动机库 id + 期望位置） |
| "换一个" | 重采样（temperature/seed），复用 `FreePracticeCandidateSession` 去重 |

## 5. 数据管线

```
PDMX(古典筛选) + OpenScore + KernScores + DCML + 众赞歌
  → MusicXML/kern → StorageScore（既有导入器）
  → 清洗：单谱表声部数一致性、极端长度/密度剔除、PDMX rating 过滤
  → 内核批量分析：调性/和声骨架（reduce）、外音分类、动机提取
  → 条件前缀 + interleaved ABC 序列 + 调性增广（±平移，NotaGen 同款 15 调）
  → train/val/test 按“曲目”切分（防泄漏）
```

- 规模估算：古典高质量子集 1–5 万曲目量级足够（NotaGen 微调仅用 9K 谱）。
- 自动标注失败的样本（分析器无法归类的外音、无法确定的调性区域）**降级为
  无条件样本**参与训练，不丢弃——条件 dropout 本来就需要无条件样本。
- DCML 人工和声标注用于**校验自动标注器**的错误率（N0 判据），不直接当训练标签。

## 6. 训练方案与成本

### 6.1 SFT（条件化继续训练）

| 项 | 方案 | 依据 |
|----|------|------|
| 规模 | NotaGen-small 110M 全参 fp16 + grad-ckpt；medium 用 LoRA(r=64) 或全参+8bit 优化器 | 官方 large(516M) 微调要求 24GB，small/medium 富余 |
| 硬件 | 单张 24GB（4090/3090）；16GB 卡走 LoRA | 无需集群 |
| 时长 | 先导 10M 模型：小时级；110M + 3–5 万曲 × 数 epoch：单卡数天 | 可接受的迭代周期 |

### 6.2 RL 后训练：规则奖励 + 偏好优化

**奖励函数 = 现有 critic 的直接复用**（这是本项目相对所有文献工作的优势——
RL-Tuner 们要手写奖励规则，我们已有完整的、带解释的规则内核）：

```
R = w_h · (无 HARD 违例 ? 0 : -大罚)            四部/旋律硬规则 finding
  + w_s · Σ SOFT finding 加权分                  平顺、音域、倾向音…
  + w_c · 条件符合度                             reduce(输出) 的和弦序列 vs %%harmony；
                                                 fig-types/density/motive 命中率
  + w_e · 外音可解释率                           NonChordToneClassifier 归类成功比例
  + w_a · 审美分（可选后期）                      CLaMP 2 嵌入距离（NotaGen 管线）
```

- **算法：离线 DPO 优先**。流程：SFT 模型对同一条件采样 n 个 → 奖励排序 →
  构造偏好对 → DPO。全离线、显存与 SFT 同级、实现即 NotaGen 脚本换奖励器；
  可多轮迭代（采样→DPO→再采样）。GRPO（在线）留作 N3 之后的可选升级，
  仅当离线迭代收益饱和时启用。
- **防奖励 hacking / 多样性坍缩**（SMART 教训）：奖励各项封顶；DPO β（KL 强度）
  从保守值起步；每轮迭代监控多样性指标（§8），跌破阈值即回退。
  尤其警惕模型学会"全和弦音、零外音"来讨好规则项——条件符合度里
  fig-density 命中是对抗项。
- **写作器的两个 RL 角色**：
  1. *修复器*：推理时兜底（§1.2），不进训练环。
  2. *数据增广*：写作器生成合法骨架 + 模型装饰，装饰质量由 critic 打分构造偏好对——
     缓解真实语料中"用户给定的进行"分布覆盖不足的问题。

### 6.3 成本总账

| 阶段 | 硬件 | 时长量级 |
|------|------|----------|
| 先导（10M 从头） | 任意 ≥8GB 卡 | 天 |
| SFT 110M | 1×24GB | 数天 |
| DPO ×3 轮 | 同上（采样占大头，可断点） | 每轮 1–2 天 |
| 推理 | fp16 ~0.3GB 权重；≤4GB VRAM 或 CPU（int8 ONNX） | 每乐句秒级 |

结论：全程单张消费卡；不租集群。预训练不自己做（复用 NotaGen 权重），
这是成本可控的关键决策。

## 7. 推理与产品集成

- **部署形态**：模型架构非标准 LLM（patch+char 双解码器），llama.cpp 不适用。
  首选 **Python sidecar**（本地进程 + localhost 端口，PyTorch/ONNX），桌面与 Web 后端
  同一进程协议；后期可评估 ONNX Runtime JVM 内嵌消除 Python 依赖。
- **接入点**：作为 `SolverApi` 的一个 neural provider——对外仍是 enumerate/solve/refine
  协议与 `SolverDiagnostic`，UI 不感知实现来自搜索还是采样。窗口写作路径：
  `FreePracticeWindowVoicer` 的槽窗编译（§4.4 条件）→ sidecar 采样 → 回读 ABC →
  critic finding → 违例窗重解 → 候选合并进 `FreePracticeCandidateSession`。
- **失败退化**：sidecar 不可用/超时 → 纯写作器路径，功能不缺失只少"装饰增强"。
- **候选呈现**：每个候选携带完整 finding/ScoreBreakdown（模型输出也过同一 critic），
  用户看到的解释协议与现有写作器结果一致。

## 8. 评测

| 指标 | 度量 | 工具 |
|------|------|------|
| 硬规则违例率 | 每候选 HARD finding 数（修复前/后） | 现有规则内核 |
| 条件符合率 | reduce 后和弦序列与 `%%harmony` 逐槽一致率；fig/motive 命中率 | 分析路径 |
| 外音可解释率 | classifier 可归类的非和弦音比例 | `NonChordToneClassifier` |
| 多样性 | 同条件 n 采样的两两编辑距离 / distinct-n | 新增脚本 |
| 往返保真 | ABC↔StorageScore 逐音相等 | N0 codec 测试 |
| 听感 | 自评 A/B（模型 vs 写作器枚举 vs 语料真值），每里程碑固定 20 条件集 | 人工 |

金标准：教材谱例（外音章各类型）作为条件输入，检查生成结果 reduce 后归类与
教材一致——与 figuration §11 的"生成-检查闭环"共用判据。

## 9. 里程碑

| 里程碑 | 内容 | 判据 |
|--------|------|------|
| **N0** | ABC codec（往返保真）+ 数据管线 + 自动标注器校验（DCML 对照）+ 评测脚手架 | 往返测试绿；标注错误率报告 |
| **N1** | 先导：众赞歌 10M 模型从头走完 SFT→DPO→集成最小环 | 条件符合率 > 无条件基线；管线无手工步骤 |
| **N2** | NotaGen-small 条件化 SFT（§4 全部条件 + infilling） | 条件符合率/外音可解释率显著优于 N1；同条件采样可用率（无 HARD 违例或可修复）> 50% |
| **N3** | 规则奖励 DPO ×N 轮 + 多样性监控 | 可用率提升且多样性不跌破 N2 水平 |
| **N4** | sidecar + SolverApi neural provider + 自由练习 UI 接线（候选/finding/换一个/pin） | 桌面端到端演示；模型缺席退化正常 |
| **N5** | 扩展：动机条件深化、复调声部生成（species 语料）、medium 升级评估 | 按 N2/N3 同款判据复评 |

N0/N1 先行是硬性顺序（去风险）；N4 可与 N3 并行开工（接口不依赖模型质量）。

## 10. 风险与开放问题

- **ABC 表达力边界**：复杂 tuplet、跨声部符杠、装饰音记号的往返损失——N0 明确
  不支持清单，训练语料按清单过滤，不追求全量。
- **NotaGen patch 结构对控制前缀的容纳**：前缀增长挤占 1024 patch 上下文；
  必要时条件压缩（和声骨架用紧凑编码而非全拼罗马数字）。
- **自动标注噪声**：分析器在浪漫派半音化片段错误率未知——N0 用 DCML 对照量化，
  必要时语料收窄到分析器可靠的风格域（巴洛克/古典优先，恰与教学场景一致）。
- **规则奖励与审美目标的张力**：全规则满分的输出可能平庸；w_a 审美项与
  fig-density 对抗项是否足够，靠 N3 的 A/B 检验，不预设。
- **中文/多语用户提示词**：本设计不含自然语言条件；自然语言→条件编译走
  roadmap §6 的 LLM 意图翻译层，与本模型解耦。
- **许可证**：NotaGen 权重 MIT、语料公有领域（PDMX 已按此筛选）；衍生权重可随
  应用分发，无云依赖。
