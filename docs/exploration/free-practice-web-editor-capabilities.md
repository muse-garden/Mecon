# 自由练习 Web 五线谱编辑能力矩阵

> 基线：2026-08-05；更新：2026-08-18。共享五线谱编辑、自由练习 session、时间轴 raw
> geometry/interaction、谱面 origin/extent 与两层工具栏桌面等价门禁均已完成。
> 钢琴卷轴按产品边界保留桌面实现。实施证据见
> [完整工作台 Web 化记录](free-practice-web-workbench-completion-plan.md)，后续改动见
> [功能扩展指南](free-practice-extension-guide.md) 与
> [Web/桌面交互对齐计划](free-practice-web-desktop-parity-plan.md)。

本文是“完整五线谱编辑”的验收清单。完整表示 Web 与桌面调用同一业务命令并得到等价
`StorageScore`、选择和撤销边界。自由练习工作台另外要求时间轴与两层工具栏使用共享规格并通过
几何/视觉门禁，不能再以“业务 intent 等价”替代产品界面等价。

状态标记：✅ 已完成门禁；🟨 已有可运行子集但能力族未完整；🚧 正在接入；⬜ 尚未接入。

2026-08-07 对齐门禁覆盖 committed drag reload、共享内容原点、末端 `＋`、toolbar descriptor、
Web 三张金标准与 Desktop Compose 离屏金标准。Node/JVM/JS trace 覆盖公共组件、坐标/选择、文件保留、
raw input 与 Kotlin/JS facade；浏览器动态导出的 `.mecon` 由桌面 `MeconDocumentService` 回读，验证
非活动乐谱、未知模块 payload 与 workspace 未丢失。

| 能力族 | 桌面入口/共享本体 | E0 协议 | Web UI/E2E |
|---|---|---:|---:|
| 选择、局部/和弦音选择、全选 | `RenderedScoreSelectionGestures` / interaction sections | ✅ | ✅ 单音头、框选、全选；框选拖动时显示半透明虚线矩形并实时染蓝框内元素，不绘制元素 hit-box 虚线框 |
| 单音、休止、和弦与跨小节插入 | `NoteEditEngine.insert/insertChord` | ✅ | ✅ 原子和弦、跨小节拆分；pointer 音高按落点时刻的谱号与调号生成，Desktop/Web 共用 `StaffPitchContext` ghost |
| 删除、音高拖动与移调 | `NoteEditEngine.delete/transpose` + renderer `TransposePreviewComputer` | ✅ | ✅ 音符 preview 由 Kotlin renderer 重刻完整符头/符杆/符尾/加线，Web 只重放命令并提交 shared intent；休止 preview + commit |
| 时值、附点、连音组、小音符 | `editDurations/applyTuplets/createSmallNoteRegions` | ✅ | ✅ 设置与更新 |
| 临时记号、延音线、符杠、发音法 | `editAccidentals/editTies/editBeaming` | ✅ | ✅ 选择模式反映并编辑所选音符；录入模式保持下一音符的默认值，Canvas ghost、pointer 与键盘/手工步进共用该值；临时记号落音后清除 |
| 倚音组 | `GraceNoteEditing` / `editGraceGroups` | ✅ | ✅ 插入与属性更新 |
| 复制、剪切、粘贴 | `NoteCopyPaste`、`SelectionClipboard` | ✅ | ✅ 按钮状态与快捷键 |
| 跨声部/谱表移动 | `VoiceMoveEngine` | ✅ | ✅ 跨谱表移动与选择恢复 |
| 谱号、调号、拍号 | `Clef/KeySignature/TimeSignatureEditEngine` | ✅ | ✅ 第 1 小节拍首规范化为初始谱号/调号状态，不生成冗余 change；后续谱号进入中央 C 时间序列；调号升降号按谱号算法定位；真实 pointer E2E 覆盖换谱号后的落音 |
| 小节插删、结构重排 | `MeasureEditEngine` | ✅ | ✅ 插入、确认删除 |
| 小节线、反复、房子、导航记号 | `Barline/RepeatStructureEditEngine` | ✅ | ✅ 房子端点、跨系统导航与删除 |
| 速度、力度、发夹、8va、文本与演奏记号 | `Tempo/ExpressionEditEngine` | ✅ | ✅ 添加、更新、删除、整体/端点拖动 |
| 连音线与几何控制点 | `SlurEditEngine`、geometry overrides | ✅ | ✅ 创建、弧高/端点、删除 |
| 换行/分页、谱表可见性 | `LayoutBreak/StaffVisibilityEditEngine` | ✅ | ✅ 设置、清除、隐藏、显示 |
| 属性面板的批量编辑与删除策略 | `SelectionInspector` contributors | ✅ | ✅ 时值/附点/记号/停顿量与删除 |
| undo/redo 与选择恢复 | `ScoreStateManager` | ✅ | ✅ 按钮语义与快捷键历史 |
| 快捷键、键盘/MIDI 步进输入 | platform adapters / shared insert intents | ✅ | ✅ 键盘与 Web MIDI 同路径；拼写与光标推进均由 session 决定 |

## 等价性门禁

每一行从 ⬜ 变为 ✅ 前必须同时具备：

1. JVM 与 JS 重放**同一份** `features/score-editing/testdata/intent-trace.json`，逐步比较规范化
   `StorageScore`、revision、selection、effect（见
   [乐谱编辑多端接入规范](../score-editing-multiplatform.md) §5）；新能力必须往该 trace 追加步骤；
2. 单次用户动作只产生一个历史项，preview 不进历史，stale revision 不改变状态；
3. 普通音符编辑保留 incremental `RenderHint`，结构编辑明确 full/reflow；
4. Web E2E 覆盖 pointer 与键盘入口，并从浏览器导出 `.mecon` 交给桌面 codec 回读；
5. 未知模块、非活动乐谱与 manifest workspace 在编辑保存后保持不变。

协议首版允许每次返回完整 score/frozen geometry；优化为 patch 时不得改变 intent 和历史语义。

## 自由练习首轮写作 MVP（`8d94ce91`）

> 2026-08-06：✅ 自由写作范围完成；勋伯格禁忌表与综合教学练习不在本轮范围。

| 能力族 | 共享本体 | 桌面 | Web / 门禁 |
|---|---|---:|---:|
| document/workspace/revision 与稳定 slot id | `FreePracticeSession` | ✅ 当前子集 | ✅ 5 步 MVP trace |
| 和弦目录、选和弦与固定低音 | `frame.catalog` + typed intent | ✅ | ✅ React 不维护和弦白名单 |
| 新里曼 / voice-leading 惯用进行页签 | `theory.voiceleading` + `PracticePlanView.voiceLeading` + `InsertVoiceLeadingChord` | ✅ 三/七和弦、1–3 步、有序原音路径、平五/平八风险、勋伯格根音方向 | ✅ 与勋伯格惯用进行分 tab；候选按 `6m 1-3-5 → 1-3-6` 展示并高亮两侧变动音；插入为独立和弦，不创建惯用进行线；JVM/JS trace 和真实 Playwright 插入路径 |
| 挂留 / 经过和弦路径 | `theory.voiceleading` 路径代数 + `PracticeVoiceLeadingPathwaySectionView` + `InsertVoiceLeadingPathway` | ✅ 稳定 / 过渡节点分层、张力剖面排序、外音标签、整条路径一次写入一个历史项；`NON_CHORD_TONE` 放置待装饰层，session 拒绝 | ✅ 与一步候选同页签；节点链高亮过渡态；置灰的“作为和弦外音”开关来自共享 `placementOptions`；JVM/JS trace 与真实 Playwright 插入 + 单次撤销 |
| 自动配声、重写、取消与 typed outcome | background request/result | ✅ | ✅ 独立 search worker；真实 JS 求解测试 |
| 候选优化与换结果 | serializable `PracticeVoicingCandidate` | ✅ | ✅ Playwright |
| workspace + score 原子历史 | `HarmonyPracticeTransaction` + 显式 score-session 通知 | ✅ | ✅ trace 覆盖 undo/redo |
| 检查反馈 | `PracticeFindingComputer` + message key/arguments + 规则领域说明 | ✅ 本地化 adapter 优先显示规则说明，ID 作为次要信息；INFO/WARNING/ERROR 使用蓝/橙/红卡片 | ✅ DOM 语义列表同样显示规则说明并保留定位入口，蓝/橙/红覆盖标题、圆点、边框与背景；JVM/JS trace 校验说明文字透传 |
| 编辑回放 | `PracticeEditPlayback` + worker MIDI excerpt | ✅ 单事件短试听/共享片段 | ✅ 音符选择与编辑、和弦及惯用进行；长惯用进行整段回放且不移动播放线 |
| `.mecon` 新建、导出与离线恢复 | 共享 `FreePracticePreset` + schema v8 payload + score 引用 | ✅ | ✅ Worker 新建、导出/IndexedDB/刷新恢复；新建归档真实浏览器回读 |
| 离线静态资源 | 平台 adapter | 不适用 | ✅ 指纹化 Service Worker cache |

自由写作 JS 不读取禁忌表；JS actual 在误调用时显式失败，禁止静默退化为空表。若后续把教学练习接入
Web，须单独完成禁忌表平台无关索引与 JVM/JS key 集合等价门禁。

## 完整工作台后续矩阵

> 当前临时槽位列表、扁平和弦下拉和 finding key 列表不等于桌面工作台等价实现。

| 能力族 | 当前状态 | 完成门禁 |
|---|---:|---|
| 受控的公共完整乐谱编辑 React 组件 | ✅ | `ScoreEditor` + `useScoreEditorController` 统一承载 surface、toolbar、domain hooks、command/click/drag controller 与 inspector；完整 fixture/自由练习共用同一 host，应用架构测试禁止低层重复拼装 |
| 工作台顶栏与谱面工具栏 | ✅ | Desktop/Web 消费同一 `FreePracticeToolbarSpec` stable-id 分组与 64/28dp token；control-id 快照和 Web 金标准通过；完整工具栏按内容占高，不会在较矮窗口被压入时间轴命中层 |
| 谱面 Bravura 音乐按钮 | ✅ | 命名 SMuFL 码位集中于 `music-glyphs.js`；工具模式、时值、倚音、连音组、符杠和奏法展开按桌面 palette 顺序接入。Web 与 Desktop 共用 `reflectSelection/editingSelection` 语义：SELECT/MARQUEE 反映并编辑公共选区属性，NOTE 维护录入默认值；临时记号、时值/附点、声部、延音线、连音组、符杠、倚音与发音法不再误改 NOTE 模式留下的上一选区 |
| 时值调板 pointer 录入 | ✅ | 点击时值始终选择 NOTE 输入工具（已有选区的时值编辑由属性控件负责），按钮用高亮背景展示选中态；Canvas hover 以桌面同款半透明灰色绘制 Kotlin renderer 的 ghost commands。自由练习的 ghost 与正式音符共同应用 aligned time axis 的 `notationContentStartGap`，Web 不维护像素补偿；合流请求按 pointer 版本丢弃过期响应且保留上一幅预览直至最新结果到达，避免坐标拖尾与闪烁。点击再把同一路径解析出的稳定声部、时码与音高以普通 `ScoreEditIntent.InsertNote` 送入共享会话。连音组首个 ghost 的音符按组内单元时值绘制（如四分三连音显示八分音符），并显示待开启的括号与数字；该换算由 Kotlin ghost 管线统一完成。首音成功后 Desktop/Web 都应用 session 的 `noteInputTransition`，切换为组内成员时值并清除启动计数，后续 ghost 依已有组内休止定位且不重复开启连音组。Esc 返回 SELECT、清除 ghost 与谱面选区。谱面工具栏固定单行横向滚动，避免换行控件命中区重叠；Kotlin/JS 坐标测试直接比较 ghost 与提交后 NOTEHEAD 的 X，真实 Playwright pointer 路径覆盖选中态、ghost、Esc、连音首音迁移与所选时值落音 |
| 复合 score update/effect/revision 正确透传 | ✅ | inner no-op/selection/effect、`scoreChanged`、stale revision 与普通音符 incremental render hint 已进入同一 JVM/JS practice trace |
| slot/layout/idiom/score 统一 selection | ✅ | 独立 intent 在 Web/桌面都经 session；frame 投影稳定 ID 与 score targets，revision 36 JVM/JS trace 验证 layout/idiom 选择、手工记谱来源及 undo/redo 恢复 |
| 和弦槽移动、左右端点、共享边界与插删 | ✅ | commonMain controller 统一 hit-test/量化/gesture/edit；JVM/JS raw trace 与真实 pointer/keyboard reload/undo/redo 通过 |
| 桌面时间轴视觉与 pointer adapter | ✅ | 共享 scene 恢复改造前的刻度、调性线、多行读法、音级、状态层次、手柄、括号与插入区；完整模式的调性区间按重叠关系交错复用轨道，精简模式隐藏调性/惯用进行轨道并在首和弦框标注进行标题；两端用始终同时显示“完整/精简”的竖向 Switch 切换，固定左侧控制区在滚动时遮挡经过左缘的内容但不移动初始和弦 X；末端 `＋` 固定排在最后一个和弦之后，不随视口悬浮且其完整最小宽度计入 scroll extent；Compose 使用稳定 pointer coroutine 读取最新 scene，代表性 golden 和拖动门禁通过 |
| 调性布局增删改选与范围拖动 | ✅ | 稳定 layout id intent、JVM/JS trace、React 表单与 pointer 双端手柄 E2E 已过 |
| 枢纽与惯用进行插入/替换/删除 | ✅ | 稳定 ID intent、目录 title、范围括号、lane、精简模式首和弦标注、锁定与选择均由共享 timeline scene 投影；浏览器插入/删除与导出回读通过 |
| 完整工作台设置与显式重建 | ✅ | 声部数/初始调性用 `RebuildPractice` 原子重建；上下谱表分配用 `UpdateStaffVoices` 迁移并可撤销；React 显式确认，写作设置已 typed |
| 自由练习拍号与插入小节 | ✅ | 顶栏 descriptor 同时提供拍号和插小节入口；未编辑时设置总体拍号，编辑后按共享选区调整目标小节。插入位置支持谱尾、所选音符后的小节线、所选小节线；谱面/workspace 单事务提交，逐小节填充空和弦槽且尾拍可留空。JVM/Kotlin-JS 共用 trace，Web Playwright 走真实顶栏点击路径 |
| 当前调性/和声选择/详情/惯用进行面板 | ✅ | `PracticePlanView` 直接提供选中槽、前后/末尾导航、追加位置、活动调性线、typed 和弦读法、锁定能力、离调读法 payload、低音候选、覆盖的惯用进行、分类和弦目录、任意/3音/4音组成音筛选及全部动态展示标签；拿坡里分类同时包含三和弦与七和弦。惯用进行默认每类只列一个基础公式，具体三/七组合保留为 session 内部变体；选中实例后顶部按共享 `selectedIdiomForm` 调整步骤形态。静态文案统一来自 `PracticePlanStrings`。和弦详情的正文、构造线路、倾向音、来源及示例音高事件由 commonMain 的 `PracticeChordDetailProjector` 一次投影，Desktop/Web 消费同一 read model；Web Worker 再把共享构造事件交给 Kotlin renderer 生成冻结谱，只读展示且不提供线路选择或应用。React 不再从 timeline/workspace 二次查找或格式化动态文案，并与桌面保持折叠分区、平铺芯片、分类目录和调性下拉组件语义一致。Web 默认分区布局把和声选择与惯用进行置于五线谱下方的等宽双栏，支持 180–560px pointer/键盘调高；右栏保留当前调性、默认展开的和弦详情与 finding，并继续支持拖宽。经典组合分支保留，待 Web 钢琴卷轴接入后开放切换。写作、重建、播放等已在上方工具栏出现的控件不重复；finding 来自独立后台 channel，并可按稳定事件锚点聚焦共享选区 |
| finding 与教学目录后台 generation | ✅ | writing 按 kind、catalog、findings 各用独立 newest-wins Worker；finding 结果校验 request/base revision/fingerprint，`frame()` 不再同步跑整谱检查 |
| 和声时间轴/五线谱统一 time axis | ✅ | renderer 输出 origin/intrinsic/surface/viewport/scroll extent；共享 raw scene 使用同一 anchors；尾部按 `PracticeTimelineView.emptySlots` 显示一个无文字、不可命中、不可选择且区别于真实空和弦槽的连续视觉填充，不按拍或小节分段；绿色追加按钮始终是末端小节线之后的独立单元，视觉位置不参与空位宽度，业务插入点则优先填充现有空位。时间线拖动完整空出无音符尾部小节时，共享 session 在同一历史项内裁掉该小节，音符及延音会阻止误删。2/4 新建真实路径同时断言 typed view、占位无语义按钮、“＋”与空位分离、添加后“＋”仍存在、拖短后的尾部补齐/整小节裁剪、视觉快照及与终止小节线的像素对齐；64 槽 ≤1 px、滚动与不同 viewport 门禁通过 |
| 宽屏双栏与窄屏 tabs | ✅ | 宽屏主区+滚动右栏、窄屏四 tabs 已落地；宽屏右栏支持 240–720px pointer 拖动、键盘方向键/Home/End 和双击复位，布局状态不进 document/undo。真实浏览器辅助技术树验证 tab/tabpanel 可见性、roving focus、方向键/Home/End，以及可聚焦五线谱的动态摘要关联 |
| 桌面全部改走共享 `FreePracticeSession` | ✅（本轮范围） | 除明确暂缓的钢琴卷轴/自动记谱 adapter 外，workbench reducer fallback 已删除，时间轴、右栏和普通五线谱编辑均走共享 intent；复音校验与手工来源更新由 session commit policy 原子执行 |
| 修改后自由练习 `.mecon` 浏览器→桌面回读 | ✅ | 真实浏览器插入惯用进行后导出；桌面回读活动 module/workspace/score，并验证 sibling score、未知 module payload 与原始 manifest workspace 保留 |

32/64 槽的共享内核通道已有 JVM 与 Kotlin/JS p50/p95 自动门禁；当前最差 p95 分别为 28.34 ms 与
34.15 ms。64 槽真实浏览器已另行验证加载预算和时间轴锚点 ≤1 px；`npm run test:e2e:soak`
已提供持续语义交互、页面错误与 heap 增长门禁；正式 30.3 分钟运行通过，late-vs-early heap 增长
低于 96 MB 门槛。本轮最终门禁为 78 项 Node/JVM-JS Web 测试、16 项 Playwright（15 通过、1 项按
环境条件跳过）、Desktop Compose 金标准和浏览器导出回读。soak 仍作为独立门禁运行。

钢琴卷轴保留桌面实现，本轮不计入 Web 完整工作台退出条件；后续接入时再新增独立能力行与 trace。

## 明确不接入 Web 的范围

| 能力 | 桌面状态 | Web 状态与原因 |
|------|----------|----------------|
| 主界面谱面和声时间轴与调性区域编辑 | ✅ 和弦分析面板可在点状和弦注释与上方区间卡片之间切换；可先在常驻下拉框选择五度圈、单音音级或多音候选，再修改谱面选择；候选随选择更新，并以单历史项可选终止旧调性。所选音级用不占排版的白底覆盖框显示，可单独关闭 | ❌ 未接入。`:plugins:chord-analysis:core` 已是 KMP（含 js target），provider、候选排序和区间变换本体跨端可用；缺的是 ① web 引擎未依赖也未注册 `ChordAnalysisPlugin`，② 开关、非焦点编辑弹层和选择标签覆盖层只有桌面实现，③ `ChordSymbolDisplaySettings.scoreDisplayMode` / 覆盖层开关是进程级可变全局，不属于 document，接 Web 前需先落到文档或会话状态。接入时按本矩阵新增能力行 |
| 顶栏 `mode`（探索/自由练习模式切换） | ✅ 桌面注入在 `history` 之后 | ❌ 不适用。Web 没有该模式概念，故**刻意不进** `FreePracticeToolbarSpec`——descriptor 的含义是"两端都必须复现的东西"。桌面侧由 `Toolbar.kt` 的 `DESKTOP_MODE_GROUP` 具名承载 |

上表以外的差异一律视为遗漏而非决策；descriptor 里出现桌面画不出的 group 时
`explorationToolbarGroupIds` 会直接 `require` 失败，与 Web 壳层
`Unsupported free-practice toolbar controls` 的抛错同义。

后续每个新编辑能力都必须按
[乐谱编辑多端接入规范](../score-editing-multiplatform.md) 完成共享 session、桌面/Web adapter、
renderer/splice 与跨端测试，并在本矩阵新增或更新对应能力行；不得在 Web 壳层复制业务算法。
