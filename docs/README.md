# Mecon 开发文档

## 快速导航

| 你想了解什么 | 文档 |
|------------|------|
| 整体架构与模块划分 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 构建、测试与依赖仓库配置 | [development.md](development.md) |
| Web 端安装、Kotlin/JS 构建、运行与 Playwright | [web-development.md](web-development.md) |
| 多端移植（Web / Android / iOS / 鸿蒙 / 平板）🚧 | [multiplatform-porting.md](multiplatform-porting.md) |
| 乐谱编辑多端接入规范（Web 轻量壳层、桌面共享 session） | [score-editing-multiplatform.md](score-editing-multiplatform.md) |
| 数据模型（四层架构） | [data_model/README.md](data_model/README.md) |
| 基础类型（Pitch / TimeCode / Duration） | [data_model/primitives.md](data_model/primitives.md) |
| 变化音和弦构造、归集与多重解释 🚧 | [data_model/chord-construction-and-interpretation.md](data_model/chord-construction-and-interpretation.md) |
| 和弦音响、多重解释与详情选择设计 🚧 | [theory/chord-detail-and-vagrant-chords.md](theory/chord-detail-and-vagrant-chords.md) |
| 和弦详情实施 Roadmap 🚧 | [theory/chord-detail-roadmap.md](theory/chord-detail-roadmap.md) |
| YAML 乐谱格式 | [data_model/score-format.md](data_model/score-format.md) |
| `.mecon` 容器格式（多乐谱 / 模块 / 冻结几何） | [data_model/mecon-container.md](data_model/mecon-container.md) |
| MusicXML 互操作 | [data_model/musicxml.md](data_model/musicxml.md) |
| 图片 / PDF 乐谱识别（OMR）接入 🚧 | [omr-import.md](omr-import.md) |
| 速度记号 / 关键帧 / 渐变播放 | [data_model/tempo.md](data_model/tempo.md) |
| 渲染引擎概览 | [renderer/README.md](renderer/README.md) |
| 增量布局与渲染契约 | [renderer/incremental-rendering.md](renderer/incremental-rendering.md) |
| Web npm 渲染包（冻结版 / 完整版） | [renderer/web-renderer.md](renderer/web-renderer.md) |
| 力度记号 / 渐强渐弱 | [renderer/dynamics.md](renderer/dynamics.md) |
| 命中拾取 | [renderer/spatial-index.md](renderer/spatial-index.md) |
| 选中高亮 / 样式覆盖 | [renderer/interaction.md](renderer/interaction.md) |
| 插件开发入门 | [plugin/README.md](plugin/README.md) |
| 自定义插件轨道 | [plugin/custom-track.md](plugin/custom-track.md) |
| 插件 SPI / 生命周期 | [plugin/plugin-framework.md](plugin/plugin-framework.md) |
| 和弦分析插件实施纪要（评审） | [plugin/chord-analysis-implementation.md](plugin/chord-analysis-implementation.md) |
| 状态管理 / 撤销重做 | [state-management.md](state-management.md) |
| 线程模型 / 并发约束 | [threading.md](threading.md) |
| 大乐谱编辑性能回归复盘与守则 | [performance/large-score-editing.md](performance/large-score-editing.md) |
| 开源开发、构建与测试 | [development.md](development.md) · [../CONTRIBUTING.md](../CONTRIBUTING.md) |
| 音频播放 | [audio/README.md](audio/README.md) |
| Rust VST / 控制轨 / Kotlin UI 集成 🚧 | [audio/vst-integration.md](audio/vst-integration.md) |
| 舞台混响 / Preview / 后台渲染与 Patch 🚧 | [audio/adaptive-rendering.md](audio/adaptive-rendering.md) |
| 乐理库 | [theory/README.md](theory/README.md) |
| 四部和声固定声部基础 | [theory/four-part/README.md](theory/four-part/README.md) |
| 写作任务与规则引擎基础设施 | [theory/writing-engine.md](theory/writing-engine.md) |
| 原位三和弦连接规则 | [theory/textbook/textbook-root-position-triad-rules.md](theory/textbook/textbook-root-position-triad-rules.md) |
| 属七和弦教材规则 | [theory/textbook/textbook-dominant-seventh-rules.md](theory/textbook/textbook-dominant-seventh-rules.md) |
| 规则目录与规则关系 🚧 | [theory/rule-catalog.md](theory/rule-catalog.md) |
| 求解器 API（表单 / 脚本 / LLM 统一协议）🚧 | [theory/solver-api.md](theory/solver-api.md) |
| 自由练习自动声部写作（✅ 已实施；锁定/refine 后续） | [exploration/free-practice-auto-writing.md](exploration/free-practice-auto-writing.md) |
| 自由练习惯用进行扩充与交互修复（✅ 已实施） | [exploration/free-practice-customary-progressions-plan.md](exploration/free-practice-customary-progressions-plan.md) |
| 自由练习窗口写作（✅ runtime；公开 refine 后续） | [theory/free-practice-window-voicing.md](theory/free-practice-window-voicing.md) |
| 规则适用场景模型 🚧 | [theory/rule-scenes.md](theory/rule-scenes.md) |
| 约束程序 DSL 🚧 | [theory/constraint-program.md](theory/constraint-program.md) |
| 约束程序多样化搜索 🚧 | [theory/diverse-search.md](theory/diverse-search.md) |
| 分层动态规划求解器（✅ 自由练习默认后端；调参与合并率 🚧） | [theory/dynamic-programming-solver.md](theory/dynamic-programming-solver.md) · [槽位扩展性评审](theory/dp-slot-scaling-review.md) |
| 勋伯格和声学练习接入 🚧 | [theory/schoenberg/schoenberg-harmony.md](theory/schoenberg/schoenberg-harmony.md) · [根音与和弦选择规则接入](theory/schoenberg/root-chord-selection-rules.md) |
| 和弦外音与装饰化层 🚧 | [theory/figuration.md](theory/figuration.md) |
| 乐理求解器后续工作（优先级 roadmap） | [theory/roadmap.md](theory/roadmap.md) |
| 脚本引擎（GraalJS）🚧 | [exploration/scripting.md](exploration/scripting.md) |
| 探索模式（notebook 式乐理学习）🚧 | [exploration/README.md](exploration/README.md) |
| 自由练习工作台 Web 首发与移动复用 | [exploration/free-practice-multiplatform.md](exploration/free-practice-multiplatform.md) |
| 自由练习功能调整与新能力接入 | [exploration/free-practice-extension-guide.md](exploration/free-practice-extension-guide.md) |
| 自由练习完整工作台 Web 化实施记录 | [exploration/free-practice-web-workbench-completion-plan.md](exploration/free-practice-web-workbench-completion-plan.md) |
| 自由练习 Web/桌面统一审计与重构（✅） | [exploration/free-practice-web-desktop-parity-plan.md](exploration/free-practice-web-desktop-parity-plan.md) |
| 自由练习 Web 工作台评审结论（2026-08） | [exploration/free-practice-web-review-2026-08.md](exploration/free-practice-web-review-2026-08.md) |
| Exploration 重构审计（2026-07） | [exploration/refactor-audit-2026-07.md](exploration/refactor-audit-2026-07.md) |
| 分层分析与创作路径总览 🚧 | [analysis/README.md](analysis/README.md) |
| 缩谱与音符映射 🚧 | [analysis/reduction.md](analysis/reduction.md) |
| 动机库与相似度 🚧 | [analysis/motive.md](analysis/motive.md) |
| 曲式与调性布局标注 🚧 | [analysis/form.md](analysis/form.md) |
| 创作路径（对照 / 配器实现）🚧 | [analysis/composition.md](analysis/composition.md) |
| 乐理分析与 AI 协作路线（MCP / skills） | [ai/roadmap.md](ai/roadmap.md) |
| 神经写作模型（规则×神经混合训练方案）🚧 | [ai/neural-writing-model.md](ai/neural-writing-model.md) |
| 即兴模块总览 🚧 | [improvisation/README.md](improvisation/README.md) |
| 即兴模块设计方案 🚧 | [improvisation/design.md](improvisation/design.md) |
| 即兴模块路线图 🚧 | [improvisation/roadmap.md](improvisation/roadmap.md) |
| 引擎演进（分析规模化 / 记谱扩展）🚧 | [engine-evolution.md](engine-evolution.md) |
| 桌面 UI | [ui/desktop.md](ui/desktop.md) |
| 乐谱编辑交互（音符录入） | [ui/score-editing.md](ui/score-editing.md) |
| 键盘与 MIDI 音符输入 | [ui/note-input.md](ui/note-input.md) |
| 表情与八度记号编辑 | [ui/expression-editing.md](ui/expression-editing.md) |
| 设置对话框 / 快捷键配置 | [ui/settings.md](ui/settings.md) |
| 开发路线图 | [roadmap.md](roadmap.md) |

## 目录结构

```
docs/
├── README.md               本文件
├── ARCHITECTURE.md         整体架构概览
├── development.md          构建、测试与依赖仓库配置
├── web-development.md      Web 安装、Kotlin/JS payload、Vite、Playwright 与排错
├── multiplatform-porting.md 🚧 多端复用审计、目标架构与移植路线
├── score-editing-multiplatform.md 乐谱编辑共享会话、Web 壳层与多端验收门禁
├── state-management.md     ScoreStateManager / 撤销重做
├── threading.md            线程模型 / 并发约束
├── performance/
│   └── large-score-editing.md  大乐谱编辑性能回归复盘与防回归守则
├── omr-import.md           🚧 图片 / PDF 乐谱识别接入
├── roadmap.md              功能路线图
├── engine-evolution.md     🚧 引擎演进设计（插件通道增量化 / StaffKind / splice 契约）
│
├── data_model/
│   ├── README.md           四层架构总览
│   ├── primitives.md       基础类型
│   ├── storage.md          存储层
│   ├── runtime.md          运行时层
│   ├── computed.md         计算层
│   ├── tempo.md            速度关键帧、引用与播放曲线
│   ├── incremental-compute.md  🚧 增量计算设计
│   ├── incremental-update.md   ✅ 增量计算契约与局部更新边界
│   ├── score-format.md     YAML 文件格式
│   └── musicxml.md         MusicXML 互操作
│
├── renderer/
│   ├── README.md           渲染管线概览
│   ├── incremental-rendering.md 增量布局、splice、分页缓存与流式输出
│   ├── traversal-audit.md  遍历热点、局部化边界与性能 TODO
│   ├── layout.md           统一布局系统
│   ├── coordinate-system.md  坐标系统 / SMuFL Y 轴翻转
│   ├── stem-and-beam.md    符杆与符杠
│   ├── dynamics.md         力度记号 / 渐强渐弱 / 谱表附着符号
│   ├── spatial-index.md    层次化空间索引
│   └── interaction.md      EventSection / StyleTrack / StyleSnapshot
│
├── plugin/
│   ├── README.md                          插件系统概览
│   ├── custom-track.md                    自定义轨道 + CalcBuilder
│   ├── plugin-framework.md                插件 SPI / 注册 / 生命周期
│   └── chord-analysis-implementation.md   和弦分析插件实施纪要（代码评审）
│
├── theory/
│   ├── README.md           乐理库（当前状态 + 设计方向）
│   ├── writing-engine.md   写作任务 / 规则结果 / 局部检查
│   ├── rule-catalog.md     🚧 规则目录 / 层级与互斥关系 / 错误示例机制
│   ├── solver-api.md       🚧 求解器 API（五入口协议 / 能力清单 / 表单渲染）
│   ├── free-practice-window-voicing.md  ✅ runtime 窗口写作 / 🚧 公开 refine
│   ├── rule-scenes.md      🚧 规则适用场景模型 / 符号级枚举
│   ├── constraint-program.md  🚧 约束程序 DSL / LinePattern / refine
│   ├── diverse-search.md    🚧 首解贪心 DFS / 多样化重启 / 距离门槛
│   ├── dynamic-programming-solver.md  ✅ 自由练习默认后端 / 🚧 宽度解耦与勋伯格 AUTO
│   ├── dp-slot-scaling-review.md  🚧 槽位扩展性测量 / 状态合并率 / 改进顺序
│   ├── figuration.md       🚧 和弦外音与装饰化层 / 拍位语义 / 还原管线
│   ├── four-part/
│   │   └── README.md       四部和声固定声部基础
│   ├── schoenberg/
│   │   ├── schoenberg-harmony.md           🚧 勋伯格和声学练习接入
│   │   ├── minor-and-no-common-tone.md      小调分支与无共同音连接
│   │   └── root-chord-selection-rules.md   根音与和弦选择规则接入
│   └── textbook/           教材规则文档
│       ├── textbook-melody-rules.md            旋律教材规则
│       ├── textbook-four-part-rules.md         四部和声教材禁则
│       ├── textbook-root-position-triad-rules.md  原位三和弦连接规则
│       ├── textbook-first-inversion-triad-rules.md  三和弦第一转位规则
│       ├── textbook-second-inversion-triad-rules.md 三和弦第二转位规则
│       └── textbook-dominant-seventh-rules.md  属七和弦教材规则
│
├── exploration/
│   ├── README.md           🚧 探索模式总览（notebook 式乐理学习）
│   ├── document-model.md   🚧 探索文档数据模型 / CellRequest / 求解执行
│   ├── ui-interaction.md   🚧 Notebook UI / 内联分析面板 / 多乐谱状态架构
│   ├── free-practice-multiplatform.md  自由练习 Web 首发 / 移动复用边界
│   ├── free-practice-extension-guide.md  自由练习调整 / 新能力端到端接入
│   ├── free-practice-web-workbench-completion-plan.md  公共编辑器 / 时间轴 / 右栏实施记录
│   ├── free-practice-web-desktop-parity-plan.md  ✅ 共享时间轴 scene / 布局 / 双工具栏统一
│   ├── free-practice-auto-writing.md  ✅ 自动声部写作 / 工具栏 / 撤销 / 回放
│   ├── scripting.md        🚧 脚本引擎（GraalJS / 沙箱 / ScriptedRequest）
│   └── refactor-audit-2026-07.md  编排拆分 + 和弦本体统一重构审计
│
├── analysis/
│   ├── README.md           🚧 分层分析与创作路径总览（层级栈 / 映射通道）
│   ├── reduction.md        🚧 缩谱与音符映射 / 一致性偏离 / 提取交互
│   ├── motive.md           🚧 动机库 / 规范形 / 变换与相似度分层
│   ├── form.md             🚧 曲式与调性布局标注（span 注释元素）
│   └── composition.md      🚧 创作路径（对照创作 / 配器实现与替换）
│
├── ai/
│   ├── roadmap.md          🚧 乐理分析与 AI 协作总体路线（规则引擎 / 搜索 / MCP / skills）
│   └── neural-writing-model.md  🚧 神经写作模型（基模选型 / 控制接口 / 规则奖励 RL / 集成）
│
├── improvisation/
│   ├── README.md           🚧 即兴模块总览（定位 / 复用关系 / 文档索引）
│   ├── concept.md          🚧 概念背景讨论记录（两维输入 / 候选卡片 / 认知模型）
│   ├── design.md           🚧 架构方案（意图词汇表 / 候选生成 / 会话调度 / UI）
│   └── roadmap.md          🚧 里程碑 I0–I5 与依赖对照
│
├── audio/
│   ├── README.md           音频引擎（JVM MIDI + 跨平台计划）
│   ├── vst-integration.md  🚧 Rust 乐器引擎、PerformancePlan、控制轨与 Kotlin UI 集成
│   └── adaptive-rendering.md 🚧 舞台混响、Preview、缓存、后台渲染与 Patch
│
└── ui/
    ├── desktop.md          桌面 UI 组件
    ├── score-editing.md    乐谱编辑交互（音符录入工具 / 虚影 / 编辑引擎）
    ├── note-input.md       键盘 / MIDI 步进与实时录入
    ├── expression-editing.md 表情、力度与八度记号编辑
    ├── settings.md         设置对话框 / 快捷键配置 / 本地存储
    ├── piano-roll.md       钢琴卷轴视图
    └── i18n.md             国际化
```

## 状态标记约定

- ✅ 已实现
- 🚧 设计阶段 / 部分实现（文档内会进一步说明）
- ❌ 不支持 / 不计划
