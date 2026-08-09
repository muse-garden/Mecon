# 探索模式 UI 与多乐谱状态架构

> 归属：`apps/desktop/ui/exploration/`、`apps/desktop/service/`。
> 数据模型见 [document-model.md](document-model.md)。
>
> 当前实现：桌面 TopBar 增加「探索」tab，`ExplorationView` 提供一个原位三和弦 request cell。
> 它支持规则优先的规则示例、进行练习、手动运行、过期提示、候选切换、只读乐谱点击选中、
> finding 着色/hover/选中联动、从头/当前/选中播放、finding 与 `ScoreBreakdown` 摘要。
> 完整 notebook cell 增删移、多乐谱可编辑宿主与持久化仍未接入。

## 1. 顶层布局

长期目标是 `App.kt` 按当前文档类型切换中心视图：`kind: score` → 现有 `ScoreView`，
`kind: exploration` → `ExplorationView`。当前首版先通过 TopBar 的「探索」tab 进入
`ExplorationView`，不改变文件控制器的单谱文档状态；探索页隐藏 LeftToolbar / RightPanel，
分析内容直接显示在 request 输出区。

输入区跨模式共享的不可变 state/actions 契约集中在
`apps/desktop/.../ui/exploration/ExplorationEditorContract.kt`；规则示例、自由进行、
勋伯格练习和转调编辑器只消费各自的子契约。契约不包含求解规则，规则仍由 exploration /
theory 模块提供。

```
ExplorationView
└── LazyColumn（cell 虚拟化滚动）
    ├── TextCellView        Markdown 简化渲染（v1：标题 / 段落 / 列表）
    ├── ScoreCellView       可编辑乐谱 + caption
    └── RequestCellView
        ├── 请求编辑区       左：规则树；右：规则驱动表单 / 进行序列 / 搜索参数
        ├── 材料谱（可选）   可编辑乐谱
        ├── 运行栏           [▶ 运行] 状态徽标（就绪/运行中/已过期/无解） 耗时
        └── 输出区（可选）
            ├── 候选切换条   「候选 1 (-2.5) | 候选 2 (-3.0) | …」chip，一次渲染一个
            ├── 只读乐谱     选中 / 高亮 / 播放
            └── CellAnalysisPanel（内联分析面板，§3）
```

cell 通用操作：hover 显示工具条（上移 / 下移 / 删除 / 在下方插入 text·score·request）。
新建入口：新建对话框增加"探索文档"选项；空文档默认含一个 request cell。

**规则优先输入**（层级 1）：请求编辑区分左右两列。左列始终按 `RuleCatalog` 渲染树：
`调性和声 / 原位三和弦连接 / 根音四(五)度关系 / 共同音保持`。用户先选规则，
右列再由 `RuleCatalog.exampleInputSpec(ruleId)` 派生控件：

- 默认和弦对：共同音保持自动落到 V→I，三/六度规则落到 I→vi，二/七度规则落到 V→vi；
  用户仍可在同关系的常用和弦对内切换。
- 调式限制：大调 V→vi 倾向固定为大调；小调升 5→4 禁则固定为小调。
- 和弦类型限制：小调升 5→4 禁则会把第 5 级固定为大三和弦 V，避免退回自然小调 v。
- 附属规则：如"内声部导音跳进"需要同时指定一个四/五度连接模式，右列显示伴随模式 chips。
- 错误示例：`demonstrableAsViolation` 规则显示"演示违规"开关；小调升 5→4 默认开启。

`RuleCatalog` 维护教材规则目录、父子关系、互斥/从属关系、applicability 与通用输入约束。
普通规则不手写表单映射：常用和弦对从 applicability 推导，伴随规则从 `REQUIRES` 推导；
特殊规则再通过 provider override 补充调式锁定、固定音级对或默认违规演示。桌面 UI 只负责
把 `RuleExampleInputSpec` 渲染成控件，并在请求编译后继续通过 `RuleCatalog.validateSelection`
统一校验。

## 2. 焦点与选中模型

进行练习表单提供练习类型 chips：原位三和弦、原位/第一转位、第一转位与四六和弦。
四六和弦练习使用逐槽音级按钮并保底三个和弦，因为终止、经过、持续音和同和弦转位插入都依赖前后文。

- **单焦点 cell**：`ExplorationSession.focusedCellId`。点击任意 cell（谱面、表单、空白处）
  即聚焦；聚焦驱动内联分析面板展开与 LeftToolbar 作用目标。
- **cell 内事件选中**：沿用现有 `EventSection` 机制，但作用域限于焦点 cell 的某一个乐谱；
  切换焦点 cell 时清空前一 cell 的选中。全局同时至多一份事件选中。
- **只读乐谱**：接受点击选中（供分析面板过滤 finding、试听局部），忽略一切编辑工具；
  当前通过 `RenderedScoreView(readOnly = true)` 保留点击/播放头/平移缩放，屏蔽音符拖拽移调与休止符移动。
- **可编辑乐谱**（ScoreCell / material）：复用现有音符录入、乐谱元素调板与
  `NoteToolState` 全局互斥工具状态；工具作用于焦点 cell 的可编辑谱。
- Esc：先清事件选中，再取消插入笔（与编辑模式一致）。

## 3. 内联分析面板（CellAnalysisPanel）

主方案：**cell 下方内联**（已确认）。

- **折叠态**（cell 未聚焦）：单行摘要，如 `⛔1 ⚠2 💡1 ✓3`（HARD / SOFT·WARNING /
  HINT / INDICATION 计数），保证浏览整个文档时信息密度。
- **展开态**（cell 聚焦）：
  - finding 列表按严重度分组；每行 = 规则名 + i18n 文案（`StoredFinding.messageKey`）。
  - hover finding 行 → 该 cell 乐谱上高亮 `anchors` / `relatedAnchors`（主/关联异色，
    已复用 `RenderedScoreView.localEventStyles` 局部 `StyleOverride` 轨道）。
  - 选中乐谱音符时列表过滤为与该音符关联的 finding。
  - `ScoreBreakdown` 明细：各规则分数贡献表，解释候选排序。
  - 错误示例 cell：`isDemonstrationTarget` 的 finding 置顶并标记"演示目标"，
    文案说明"此违规为你所要求的演示"。
- 实现路径：把 `plugins/theory-analysis/desktop` 中 finding 列表 / hover 联动抽成可复用
  composable（放 `apps/desktop-ui-kit` 或插件共享模块），编辑模式右栏与探索模式内联面板
  消费同一组件，不重复实现。

## 4. 多乐谱状态架构（关键改动）

现状假设"单文档单乐谱"：`GlobalScoreState` 单例持有唯一 `ScoreStateManager`，
`ScoreSession` 绑定唯一渲染管线。探索模式需要 N 个乐谱并存：

### 4.1 每 cell 乐谱宿主

```kotlin
// apps/desktop/service/
class ExplorationSession(document: ExplorationDocument) {
    val cells: StateFlow<List<CellUiState>>
    val focusedCellId: CellId?
    fun runCell(id: CellId)            // 编译请求 → 后台求解 → 替换 output
    fun cancelRun(id: CellId)
    // cell 增删移、文档保存、fingerprint 过期重算
}

sealed interface CellScoreHost {
    class Editable(val manager: ScoreStateManager) : CellScoreHost   // 每 cell 独立撤销栈
    class Readonly(val state: ScoreState) : CellScoreHost            // 一次构建，不入历史
}
```

实际实现先抽取通用 `EditableScoreHost`：主文档 `ScoreSession` 与自由练习
`HarmonyPracticeScoreHost` 都委托该宿主，复用后台 compute、增量 `RenderHint`、
音符编辑和历史，而不是为 exploration 复制一套 session。

- **Editable**：ScoreCell / material 各持一个 `ScoreStateManager`（独立 50 步撤销栈）。
  Ctrl+Z 作用于焦点 cell 的可编辑谱。cell 结构操作（增删移 cell）的撤销 🚧 v2，
  v1 删除 cell 前弹确认。
  每次 `commitNewState` 后由 `ExplorationSession` 重算相关 request cell 的 fingerprint。
- **Readonly**：输出候选 / 只读导入材料。加载或求解完成时执行一次
  `StorageScore → RuntimeScore → ComputedScore`，此后不变；配一个独立的
  `StyleOverrideManager` 承担选中与 finding 高亮（声明式样式系统本就按实例工作，可直接复用）。

### 4.2 GlobalScoreState 重构（前置）

`GlobalScoreState.activeManager` 的隐式"唯一乐谱"假设必须打破。方案：

- 引入 `ActiveScoreContext`（`CompositionLocal` + session 级 provider）：交互代码
  （编辑引擎入口、快捷键、播放）从 context 取当前作用目标，而非全局单例。
- 编辑模式下 context 恒为主文档 manager，行为不变；探索模式下随焦点 cell 切换。
- `GlobalScoreState` 保留为兼容外观，内部委托 context；清点现有调用方逐一迁移
  （改动面主要在 `App.kt`、交互逻辑与插件面板上下文）。

当前代码中的 `GlobalScoreState` 实际调用仅剩 `EventSectionFactory` 的旧 RuntimePitchEvent
扩展；迁移时优先让这些扩展显式接收 `RuntimeScore/ComputedScore`，再删除全局依赖。
`ActiveScoreContext` 位于 desktop 层，不能让 common `:api` 反向依赖 Compose。

### 4.3 渲染与性能

- 每个乐谱沿用现有管线 `ScoreLayoutEntry.computeLayout → RenderEngine.renderUnified →
  ComposeScoreRenderer`，探索文档内乐谱均为小片段，默认连续单 System 布局，按 cell
  宽度排版。
- `LazyColumn` 天然只组合可见 cell；`RenderResult` 以 `(computedScore 标识, 宽度)` 为 key
  缓存于 host，滚动回来不重排。
- 输出候选切换：K 个候选各自独立 Readonly host，惰性构建（首次切到才 compute+layout）。

### 4.4 运行与过期

- `runCell`：置状态 RUNNING（转圈、可取消）→ Compute dispatcher 执行编译 + beam search →
  合成输出乐谱 → 构建 Readonly hosts → 原子替换 `CellOutput` 并落新 fingerprint。
- 过期表现：输出区灰色蒙层 + "已过期，重新运行" 徽标；旧输出仍可查看与播放（明确标注
  基于旧输入）。层级 3 的引用级联过期见 [document-model.md](document-model.md) §6。
- 同一时刻允许多 cell 并行运行；同一 cell 重复点击运行则先取消前次。

## 5. 播放

- 每个输出候选自带播放按钮，走现有 `PlaybackController` / `AudioEngine` 播放该谱的 RuntimeScore。
- TopBar 全局播放控制作用于焦点 cell 的当前可见乐谱。🚧 当前首版先在输出候选内提供播放入口。
- 错误示例场景的核心体验是"听出问题"：候选切换条旁提供"连播对比"🚧 v2
  （依次播放各候选 / 正误对照）。

## 6. 与编辑模式融合的接缝 🚧

- 编辑模式选区右键 → "在探索模式中打开"：新建探索文档，首 cell 为 `ScoreCell`
  （复制选区内容，记录 `sourceFile + TimeRange` 来源锚点）。
- 双向联动（源谱改动同步进探索文档、探索结果写回源谱建议轨道）后置，
  写回路径届时复用 ai/roadmap §7.2 `apply_candidate` 的"建议轨道 + 用户确认"约束。

## 7. 开放问题

- 候选对比的最终形态（chip 切换 vs 并排小谱 vs 叠加显示）——与 ai/roadmap §10
  "多候选展示"同题，先做 chip 切换，原型后定。
- 表单式请求编辑器与 LLM 生成请求（E6）共用同一 `CellRequest` 校验与错误提示通道，
  错误文案需机器可读（供 LLM 自纠正）与人类可读兼顾。
- 探索文档是否进多文档窗口体系（当前应用为单文档单窗口）——暂沿用单窗口，
  与 desktop.md §10 "多窗口/多文档"一并演进。

嵌入式 `EditableScoreHost` 的纯伴随状态提交可能产生结构相等的 `ScoreState`。宿主在每次
commit/undo/redo 后显式递增版本，不能依赖 `StateFlow` 对相等值发出通知。
