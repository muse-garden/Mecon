# 即兴模块（Improvisation）🚧 设计

> 状态：**设计阶段，未实现**。本目录是即兴模块的文档入口。

## 一句话定位

> 一个把即兴中的**结构选择**（用户）、**听觉判断**（用户）和**具体实现**（系统）分离开的实时作曲乐器。

用户在两个正交维度上做高层选择——"音乐往哪里走"（结构意图）与"音乐怎样表达"（表面意图）；
系统调用现有约束程序求解器实时生成合法、可解释的候选并完成具体音符实现。
即兴模块**不是新求解器**，而是 `ConstraintProgram` 求解管线的第一个实时消费者。

## 文档索引

| 文档 | 内容 |
|------|------|
| [concept.md](concept.md) | 概念背景讨论记录（爵士/巴洛克即兴的认知模型、两维输入、候选卡片等设想来源） |
| [design.md](design.md) | 可落地架构方案：分层、意图词汇表、候选生成、表面实现、会话调度、UI |
| [roadmap.md](roadmap.md) | 里程碑 I0–I5、验收判据、与乐理/音频 roadmap 的依赖对照 |

## 与现有系统的关系

```
即兴会话 UI（apps/desktop）
    ↓ ImprovIntent（结构 × 表面）
:improv 会话引擎（新模块）
    ↓ ConstraintProgramSpec
:exploration SolverApi.solve ──→ :theory ConstraintProgramSolver（复用）
    ↓ 骨架候选（ChordVoicing 序列 + RuleFinding 解释）
:improv SurfaceRealizer（新）──→ 后续接 :theory figuration Stage 2（复用）
    ↓
StorageScore 增量 + 决策插件轨 ──→ 现有渲染 / 播放 / 编辑管线（复用）
```

复用清单：候选搜索与剪枝（[../theory/constraint-program.md](../theory/constraint-program.md)）、
结果多样性（[../theory/diverse-search.md](../theory/diverse-search.md)）、
规则解释同源（RuleFinding → 候选卡片证据）、终止式/五度圈/阻碍进行等已接入场景
（[../theory/rule-scenes.md](../theory/rule-scenes.md)）、乐谱落地与播放
（[../audio/README.md](../audio/README.md)）。
