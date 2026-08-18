# 乐谱编辑多端接入规范

> 状态：✅ Web 五线谱编辑是轻量壳层；桌面普通记谱入口已收敛到共享 session（见 §1.1）。
> 本文同时是后续新增编辑能力的强制接入路径——新能力从第一天就按本文接入，不得恢复平台旁路。
> 手机/Pad 的界面、触控命中、拖动与屏幕钢琴设计见[移动端交互方案](ui/mobile.md)。

## 1. 唯一业务本体

乐谱编辑的业务入口是 `features/score-editing` 的 `ScoreEditingSession`。桌面、Web 与后续移动端
只负责把平台输入转换为同一组 `ScoreEditIntent`，不得各自实现音乐规则或维护独立撤销历史。

```text
Desktop Compose ─→ ScoreSession adapter ─────────────┐
Web React ─→ Worker ─→ Kotlin/JS facade ─────────────┼─→ ScoreEditingSession
Mobile UI ─→ in-process platform adapter ────────────┘         │
                                                               ▼
                                                  core edit engines
                                                               │
                                  Storage → Runtime → Computed → Render
```

当前关键位置：

| 职责 | 位置 |
|---|---|
| 跨端 intent、selection、effect、session | `features/score-editing/src/commonMain/` |
| 不可变编辑算法 | `core/src/commonMain/kotlin/com/mecon/core/engine/edit/` |
| 桌面适配 | `apps/desktop/src/main/kotlin/com/mecon/desktop/service/ScoreSession.kt` |
| Web 字符串边界 | `bridge/web-engine/src/commonMain/` |
| Web 串行执行与发布不可变帧 | `web/apps/free-practice/src/engine-worker.js` |
| 公共 Web 编辑组件、指针与输入适配 | `web/packages/web-renderer/editor/` |
| Web 工作台 composition root | `web/apps/free-practice/src/` |
| 冻结命令重放、命中与文件容器 | `web/packages/frozen-score/` |
| Kotlin/JS facade 包装 | `web/packages/web-renderer/` |

### 1.1 桌面收敛状态

2026-08-06 起，桌面普通记谱入口中的音符、结构、表情、布局、几何、选择与 clipboard 操作均由
`ScoreSession` / `EditableScoreHost` 转换为 `ScoreEditIntent`，消费 session 返回的 selection、effect、
revision 与 render hint。自由练习谱面再由 `FreePracticeIntent.Score` 包裹同一内层 intent。

`ScoreSession.applyStorageEdit` 仍用于插件轨道、配器、缩谱等尚未属于 score-editing 协议的文档域；
它的存在不授权新增普通记谱旁路。`ScoreEditingSession.synchronizeExternalState()` 暂时保留，用于共享同一
`ScoreStateManager` 时识别这些外部文档域提交。只有相关领域也迁入明确协议后，才能删除该兼容同步。

新增记谱能力必须直接按 §3 接入 `dispatchSharedEdit`，不得先写 Compose 引擎调用再计划迁移。评审时应
区分“非记谱文档域提交”和“本应是 `ScoreEditIntent` 的平台旁路”。

## 2. Web 只能是轻量壳层

Web 壳层可以：

- 管理 DOM、工具栏、焦点、浏览器文件选择/下载、IndexedDB 恢复和 Web MIDI 生命周期；
- 重放 `FrozenScoreBundle`，执行命中测试，把 pointer 像素转换为稳定 ID 或候选音乐坐标；
- 绘制不入历史的瞬时 preview，并把普通 JSON intent 发给 Worker；
- 展示 session 返回的 score、selection、effect、revision 与渲染帧；
- 为交互体验过滤明显无效的候选，但共享 session 必须再次校验同一不变量。

Web 壳层禁止：

- 直接增删或改写 `StorageScore` / event / track，或在 JavaScript 中实现第二套 undo/redo；
- 决定时值拆分、跨小节连线、变音记号、符杠、连音组、房子范围等音乐语义；
- 复制 `NoteEditEngine`、`ExpressionEditEngine`、`MeasureEditEngine` 等核心算法；
- **重写共享层已有的乐理换算**：MIDI 音高拼写发 `midiNote`（由 `Pitch.fromMidi` 拼写），时值表、
  附点累加、按拍号进位一律不在 JS 复刻——步进光标读 `nextInputPosition`；
- **复制共享层的量值常量**：如连音线顶点上下限属于 `SlurGeometry.MIN_APEX/MAX_APEX`，由 session
  在提交时钳制，壳层不得自带一份数字；
- 把像素坐标、数组下标或仅本帧有效的对象引用写进业务协议；
- 在 React 主线程运行全谱计算或完整排版；
- 让 Web 侧候选过滤成为唯一校验，绕过 stale revision、目标存在性或结构约束检查。

浮点是有损的：手填拍位走 `quarterBeatFraction`，它接受 `"1/3"` 这类精确比值，因为三连音位置没有
有限小数表示；把 session 返回的位置写回输入框用 `formatQuarterBeat`，保证往返不丢精度。

审计判据不是“Web 代码少”，而是所有持久化变化都能追溯为：
`plain intent → Worker → MeconScoreEditor → ScoreEditingSession → shared edit engine`。

## 3. 新编辑能力的接入顺序

### 3.1 数据与核心算法

1. 若改变存储字段或 `.mecon` 语义，先更新 `docs/data_model/`，再修改 `@Serializable`、全 `val`
   的 Storage 类型；补旧文件读取、默认值及未知 payload 保留测试。
2. 在 `core/.../engine/edit/` 实现平台无关的不可变变换。算法不得依赖 Compose、DOM、Canvas、
   JVM 文件 API 或浏览器对象。
3. 音乐合法性和规范化在共享层定义一次；平台 adapter 可以提前提示，但不能代替共享校验。

### 3.2 协议与会话

1. 在 `ScoreEditProtocol.kt` 增加可序列化 intent；目标使用 `EventId`、`TrackId`、measure/time、
   staff/voice 等稳定音乐身份，不使用像素或集合下标。
2. 新增可选中的元素时，在 `ScoreSelection.kt` 的 `ScoreSelectionTarget` **sealed 层级里加一个变体**，
   只带该类型真正需要的字段；**禁止**往公共结构里加可空字段，也禁止让平台按 kind 猜测哪些字段有效。
   跨类型的通用读取用 `eventIdOrNull` / `voiceTrackIdOrNull`，行为分叉时写穷尽 `when`。
3. 在 `ScoreEditingSession.dispatch` 接入共享引擎并统一检查 revision、目标与参数。
4. 一次用户 commit 只生成一个历史项；preview、hover、drag move 不入历史，pointer up 才提交。
5. 明确定义 no-op、stale、conflict、selection restore 和失败 effect；失败不得留下部分修改。
6. 返回准确的 affected range 与 render hint。局部编辑优先 incremental；结构变化才 full/reflow。
7. **顺序输入位置由 session 决定**：插入 / 粘贴后用 `ScoreEditUpdate.nextInputPosition` 告知客户端
   光标去处（已计入连音比例、附点与跨小节拆分）。平台不得自行推算下一位置。
8. **一次性输入工具状态也由 session 决定**：连音组首音提交成功后，
   `ScoreEditUpdate.noteInputTransition` 统一给出后续成员时值并清除连音启动计数。Desktop/Web 只应用
   该迁移；不得各自调用 `tupletSpecFor` 或在 JS 重算成员时值。stale/no-op 不返回迁移。
9. **`scoreChanged` 必须诚实**：只有真正提交了新乐谱才为 true。客户端据此复用上一帧排版，谎报会让
   界面停留在旧版面。选择变化、复制、no-op、stale、conflict 均为 false。

### 3.3 Computed、Renderer 与冻结协议

1. 新元素是否存在由 Computed 层决定；Renderer 只排版，不在平台绘制器补音乐逻辑。
2. 为可编辑元素提供稳定 identity、metadata 和 hit box，使所有平台能得到同一选择目标。
3. 新增/改变 `RenderElementType` 时同时核对 `ContinuousRenderSplicer` 与
   `PaginatedRenderSplicer` 的归属、平移复用和 fail-safe 契约，并做增量/全量等价测试。
4. 优先组合现有 `RenderCommand`。若新增绘图原语，必须同步 Kotlin wire、Canvas、SVG 和 fixture；
   只有不兼容变化才提升冻结几何 schema version。

### 3.4 平台接入

Desktop：在 `ScoreSession.kt` 将 Compose 操作转换为新 intent。Compose 只保留工具状态、像素采样、
preview 和平台输入，不得直接调用核心编辑引擎绕过 session。

Web：React/interaction helper 只补控件、命中映射、pointer/keyboard/MIDI adapter 与 preview；发送
普通 JSON intent。除非协议传输本身变化，Worker 的串行队列和 facade 不应出现功能专属算法。

Android/iOS/其他端：优先在进程内直接使用 `ScoreEditingSession`，无需为内部调用复制 JSON facade；
只实现触控、键盘、文件、音频等平台 adapter，并复用相同 intent trace 测试。

如果某能力明确不支持某端，必须在能力矩阵标出范围和原因；不得只实现桌面后静默遗漏 Web。

## 4. 必须同时更新的检查表

- [ ] 数据模型文档、兼容读取与 `.mecon` 保留语义（如适用）
- [ ] commonMain 不可变编辑算法与单元测试
- [ ] intent/selection/effect codec 与 session dispatch
- [ ] revision、no-op、失败原子性、单历史项、undo/redo 选择恢复
- [ ] Computed 生成职责、renderer metadata/hit box 与两种 splice 契约
- [ ] 桌面 adapter 和真实 UI 入口（经 `dispatchSharedEdit`，不新增普通记谱旁路）
- [ ] Web JSON intent、控件、pointer/keyboard 入口与瞬时 preview
- [ ] 追加 `testdata/intent-trace.json` 步骤（JVM/JS 共享 golden trace）与浏览器 E2E
- [ ] 能力矩阵及相关 UI/renderer 文档

## 5. 验证门禁

最小门禁按改动范围执行：

```powershell
.\gradlew.bat :features:score-editing:jvmTest
.\gradlew.bat :features:score-editing:jsTest
.\gradlew.bat :renderer:jvmTest
cd web
npm run test:engine   # 先构建 Kotlin/JS 再跑 npm test，二者不可分开
npm run build --workspace @mecon/free-practice-web
npm run test:e2e
```

### 跨端等价：共享 golden trace

`features/score-editing/testdata/intent-trace.json` 是**唯一**的跨端等价依据：同一串 intent 由
`SharedIntentTraceTest`（JVM）与 `web/packages/web-renderer/test/intent-trace.test.js`（Kotlin/JS）
各自重放，逐步比较规范化 `StorageScore`、revision、selection、effect、`nextInputPosition`、
`noteInputTransition`、`scoreChanged` 与 render hint。引擎生成的随机 id 按 key 排序遍历的首次出现顺序
归一，因此两端标注一致。

- 改动协议或引擎行为后，**只从 JVM 侧**重刷：
  `.\gradlew.bat :features:score-editing:jvmTest "-Pscoreediting.trace.write=true"`，然后不带开关重跑校验。
- 新增编辑能力时**必须往 trace 追加步骤**，否则该能力不受跨端保护。步骤可用
  `@sel:0.eventId`、`@event:<voiceTrackId>:<index>`、`@id:<n>` 引用前一步产生的 id。
- 生成的 Kotlin/JS bundle 缺失时，引擎测试会**报错而不是跳过**；确需跳过用 `MECON_SKIP_ENGINE_TESTS=1`，
  但那样等于放弃这一层保护。

涉及文件存取时，从浏览器导出 `.mecon`，再由桌面 `MeconDocumentService` 回读，并验证非活动乐谱、
未知模块 payload 与 workspace 未丢失。

真实指针能力必须有 Playwright pointer 路径，快捷键必须有键盘路径；仅点击测试用按钮不算接入。
详细能力状态见 [自由练习 Web 五线谱编辑能力矩阵](exploration/free-practice-web-editor-capabilities.md)。

## 6. 评审时快速判断

- 删除 Web/桌面 adapter 后，业务语义是否仍由 commonMain 测试完整表达？
- 同一 intent 在 JVM 与 JS 是否产生相同结果和历史边界——**新能力是否进了 golden trace**？
- UI 是否只提交 stable identity 与音乐坐标，而没有提交像素？
- session 是否独立重验 UI 曾过滤的条件？
- 新元素是否同时维护 Computed、命中 metadata、冻结重放与 splice 契约？
- Web Worker 是否仍是唯一编辑执行入口，React 主线程是否仍只消费不可变帧？
- 新增选择类型是否是 sealed 变体，而不是往公共结构再加一个可空字段？
- 平台里有没有出现共享层已有的乐理换算或量值常量的第二份实现？
