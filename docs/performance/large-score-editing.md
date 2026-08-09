# 大乐谱编辑性能回归复盘（2026-07-27）

## 1. 现象与结论

约 73 页、6.6 万渲染元素的大总谱上，单音移调出现：

- 手势提交后约 1 秒无响应，随后渲染才开始；
- 新旧音符短暂重叠，期间乐谱不能拖动；
- “乐谱更新中”在编辑完成后才闪现；
- 播放 / 停止也会被播放时间线构建拖慢。

日志显示编辑引擎约 6ms、增量布局约 53ms、增量渲染约 149ms；瓶颈不在音符编辑，
而在渲染前的 Compose 主线程全谱派生，以及分页 splice 的全局安全检查退化。

修复后的关键判据是：普通非 reflow 音符编辑保持 `spliced=true`，UI 线程不执行全谱
MIDI / 播放时间线构建，更新提示覆盖到完整 `RenderResult` 发布。

## 2. 回归来源

以下提交各自引入了合理功能，但缺少“大谱热路径不得做 O(N) 主线程工作”和
“新增元素必须扩展分页 splice 契约”的约束；乐谱规模与功能叠加后形成回归。

| 提交 | 引入的路径 | 本轮影响 |
|---|---|---|
| `07959784` `feat: grace note`（2026-06-02） | `PianoRollView` 在 `remember(runtimeScore)` 中同步执行 `ScoreToMidiConverter.convert` 与音符矩形构建 | 默认展开的钢琴卷帘在每次编辑重组时扫描整谱，是约 1 秒前摇的主要来源 |
| `d30452bb` `feat: 钢琴卷轴标记拍点/小节线`（2026-06-05） | 同步构建全谱小节 / 拍点网格 | 与上项叠加，扩大主线程工作 |
| `7fbc04f3` `feat: 拖动平移音符音高`（2026-06-12） | 对 `RuntimeScore` / `ComputedScore` 使用 `rememberUpdatedState` | Compose 默认结构相等策略会在单音编辑时递归比较大不可变帧 |
| `12ca2a3b` `feat: B+树缓存中间结果；assemble优化`（2026-07-13） | 缓存 `paginatedSpliceSafe`，遇到白名单外 hittable 元素整谱 bail | 判断本身 O(1)，但白名单与分页实际可重生成能力不一致 |
| `fb28a409` `feat: 添加/删除小节逐页渲染`（2026-07-13） | 首个流式页面到达即清除 `renderInFlight` / 提示 | 完整结果尚未发布时提示已消失，慢帧下表现为延迟闪现 |
| `d0261ba8` `feat: 反复记号插入、展示、播放`（2026-07-23） | 新增 hittable `NAVIGATION_MARK`，并在 Compose 中同步构建播放时间线 | 导航记号未登记分页 splice，触发 `cachedRichUnsafe`；时间线又增加播放与编辑主线程开销 |

`84dcef44`（2026-01-21）已将底部面板设为默认展开、钢琴卷帘设为默认插件；它不是昂贵
计算的引入提交，但使 `07959784` / `d30452bb` 的同步路径始终处于编辑重组链上。

这不是单一提交造成的性能问题，而是三个契约长期缺失：

1. Compose 状态键与大不可变帧之间没有明确的引用相等约束；
2. 非主视图派生数据没有统一的后台构建约束；
3. `RenderElementType` 扩展与 paginated splice 能力表、等价测试没有绑定。

## 3. 本轮修复

### 3.1 Compose 主线程

- `ScoreRenderPipeline.rememberReferentialUpdatedState` 用引用相等保存大帧；
- `RenderedScoreView` 的 `score` / `computed` 长生命周期回调改用该状态；
- `PianoRollView` 通过 `produceState + Dispatchers.Default` 后台构建 MIDI、音符矩形与节拍网格，
  以引用 identity key 触发，50ms 可取消防抖合并连续编辑，完成前保留旧帧；
- 播放时间线仅在 PLAYING / PAUSED 时异步构建，停止态编辑不再支付该成本。
- 播放位置索引同样在后台构建；批量 TimeCode 换算只扫描一次小节 offset，禁止在每个
  谱面时间点上重复构建全谱 offset。

纯 CPU 转换开始后不保证协作取消，因此防抖必须位于进入后台转换之前。若以后支持连续高频
编辑且单次转换仍很慢，应再将转换改为可取消的分段算法或串行 conflated worker，不能依靠
无限并发的 `Dispatchers.Default` 任务掩盖问题。

### 3.2 分页增量渲染

- paginated splice 使用自身的可重生成元素表，不再直接受 continuous splice 的窄表限制；
- 已有系统归属、可在受影响系统重生成 / 复用的导航、速度、排练、延音等内建元素不再触发
  `cachedRichUnsafe`；
- `GROUP`、自定义相交测试和未知元素仍 fail-safe，不能未经证明直接复用；
- `PaginatedIncrementalLayoutTest` 增加含导航记号的局部音符编辑，要求
  `IncrementalRenderPath.INCREMENTAL`、`spliced=true`，并与全量渲染命令多重集等价。

### 3.3 提示与诊断

- 首个流式页面只更新页面内容，不结束 render generation；
- 仅完整 `RenderResult` 发布后解除交互保护并隐藏“乐谱更新中”；
- `compose.score.phase` 探针区分乐谱视图函数体阶段。`SideEffect` 的总耗时可能包含父级同批次
  的兄弟 composable，不能仅凭 `compose.score` 数值认定 renderer 慢。

## 4. 防回归规则

### Compose / 派生数据

- 禁止在 composable、`remember(score)`、状态 getter 或主线程回调中做全谱扫描、转换、排序；
- `RuntimeScore`、`ComputedScore`、`RenderResult` 等大帧作为 Compose key / state 时使用
  `rememberIdentityKey` 或引用相等策略，不能触发结构相等；
- 派生数据只在对应 UI / 功能活跃时构建，并在后台生成不可变帧；新帧就绪前保留旧帧；
- 播放启动、停止和指针事件不得等待全谱 MIDI、播放时间线或可视化索引构建。

### Renderer 扩展

新增或改变 `RenderElementType` 时必须同时回答：

1. 它由哪个 pass 生成，是否携带 `systemIndex` / staff 归属？
2. 受影响系统能否局部重生成，未受影响系统能否平移复用？
3. 是否要加入 continuous / paginated splice 的能力表？
4. 是否有“包含该元素的普通音符编辑仍 splice 且全量等价”测试？

只扩枚举或 renderer、未更新上述契约和测试，视为功能未完成。安全表应描述实际能力，
不能把“当前没测过”永久等同为整谱 bail，也不能为追求命中率放宽未知 / 插件元素。

### 性能验收

涉及乐谱编辑、底部 / 右侧面板、播放派生数据或新渲染元素的改动，至少用一份大总谱检查：

- `NoteEditEngine` 与提交阶段没有异常增长；
- `compose.app` / `compose.score.phase` 中没有新的 O(score) 主线程阶段；
- 普通非 reflow 编辑为 `streaming-incremental(hint)` 且 `spliced=true`；
- 不出现 `cachedRichUnsafe` 或未解释的全量 assemble；
- 更新提示能在慢帧期间出现，并持续到完整结果发布；
- 渲染期间仍可拖动，播放 / 停止不等待时间线构建。

相关实现：

- `apps/desktop/.../views/ScoreRenderPipeline.kt`
- `apps/desktop/.../views/RenderedScoreView.kt`
- `apps/desktop/.../views/PianoRollView.kt`
- `renderer/.../render/PaginatedRenderSplicer.kt`
- `renderer/.../snapshot/PaginatedIncrementalLayoutTest.kt`
