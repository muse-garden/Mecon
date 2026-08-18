# 移动端交互调研与 UI 方案

> 调研日期：2026-08-18；状态：🚧 产品与交互设计，移动应用尚未实现。
>
> 本文定义手机与 Pad 的主打谱、自由练习及触控输入。业务边界以
> [多端移植设计](../multiplatform-porting.md)、[乐谱编辑多端接入规范](../score-editing-multiplatform.md)
> 和[自由练习多端方案](../exploration/free-practice-multiplatform.md)为准；本文不另造编辑协议。

## 1. 结论

1. **Pad 承诺领域功能等价，不复制桌面排版。** 横屏可接近桌面工作区；竖屏、分屏和软键盘出现时
   动态折叠面板。所有共享记谱与自由练习能力都必须有入口，平台专属的多窗口、JVM 插件等另列原因。
2. **手机保持文件与业务模型完整，工作流有意收窄。** 首页围绕随手记录、音乐实验、短谱/缩谱分析；
   同一时刻只突出记录、编辑、分析或试听之一，不把桌面多栏缩小后塞进屏幕。
3. **输入策略按本次 pointer 的能力决定。** 笔用于精确选择、落音和拖动；手指默认导航，编辑时先选中
   再拖语义手柄；外接键鼠/MIDI 恢复对应快捷入口。不能只按“手机/平板”做一次性模式切换。
4. **直接操作必须可撤销、可取消且有确定性替代。** 拖动显示 ghost、语义读数与吸附，抬手只提交一次；
   音高、时间、长度等同时提供步进按钮或属性表单。
5. **平台只负责交互适配。** 像素命中、放大镜、手柄和 preview 属于 UI 瞬态；持久化仍进入
   `ScoreEditingSession`，自由练习进入 `FreePracticeSession`，不得直接修改 `StorageScore`。

## 2. 主流产品调研

本次只采用厂商手册/帮助中心，关注交互范式而非营销功能清单。

| 产品 | 移动端做法 | 对 Mecon 的启示 |
|------|------------|-----------------|
| StaffPad | 明确“笔写/改，手指导航/选小节”；笔按住音符后纵向改音高，线状元素使用端点，另有 Pencil 套索。[概念](https://staffpad.zendesk.com/hc/en-us/articles/360002333477-StaffPad-Concepts) · [元素选择](https://staffpad.zendesk.com/hc/en-us/articles/360002336558-Selecting-Items) · [套索](https://staffpad.zendesk.com/hc/en-us/articles/360002336578-Lasso-Selection) | Pad 的笔/手分工最能消除“平移还是移动元素”的歧义，但不能让无笔用户缺失能力。 |
| Sibelius Mobile | 空白拖动平移、对象拖动移动；长按空白框选。浮动 Keypad 记忆横竖屏位置，影子音符预览后抬起提交，Pencil 可与另一只手协作。[官方移动手册](https://resources.avid.com/SupportFiles/Sibelius/2025.10/Using_Sibelius_for_mobile.pdf) | 借鉴“预览—修正—提交”和可停靠输入面板；不要依赖难发现的多击手势作为唯一入口。 |
| Dorico for iPad | 用 Write/Engrave 等阶段与可折叠 zone 控制复杂度；底部可切钢琴、指板、鼓垫和属性；用 Add/Extend Selection 按钮替代修饰键。[音符输入](https://www.steinberg.help/r/dorico-for-ipad/6.1/en/dorico/topics/write_mode/write_mode_note_input/write_mode_note_input_inputting_notes_t.html) · [下方区域](https://www.steinberg.help/r/dorico-for-ipad/6.1/en/dorico/topics/write_mode/write_mode_panels/write_mode_lower_zone_r.html) · [选择](https://www.steinberg.help/r/dorico-for-ipad/6.1/en/dorico/topics/write_mode/write_mode_selecting/write_mode_notes_notation_selecting_deselecting_t.html) | Pad 用上下文区域保留完整能力；将音乐语义编辑与图形微调分开。 |
| Fender Notion | 从手机到大屏采用自适应布局；区分笔与手指，支持屏幕钢琴/指板/鼓垫和步进、MIDI、手写等输入。[官方说明](https://support.presonus.com/hc/en-us/articles/9948543112589-Notion-Mobile-is-here) | 共用能力、不共用固定排版；首版先把触控笔当精确 pointer，手写识别单独立项。 |
| Flat | 移动端把文档操作置顶、记谱工具和多点迷你键盘置底；选中音符后，可不精确按住音头而直接纵向拖动。[触屏输入](https://help.flat.io/en/education/music-notation-software/inputting-your-first-notes/) · [移动拖音](https://blog.flat.io/updated-drag-n-drop-of-notes-on-mobile/) | 选中范围可成为音高拖动代理，避免反复命中很小的音头。 |
| Logic Pro for iPad | Piano Roll 用显式编辑工具改变 tap/drag 语义；Play Surface 可切钢琴、鼓垫、指板、和弦条并调高度，支持调式限制。[Piano Roll](https://support.apple.com/guide/logicpro-ipad/piano-roll-editor-overview-lpip2bd3cd79/ipados) · [演奏界面](https://support.apple.com/en-lamr/guide/logicpro-ipad/lpip9ac51271/ipados) | 输入面板应是正式、可调整的工作区；模式必须可见。 |
| GarageBand for iPhone | 先选 Sound/活动，再进入近乎全屏的 Touch Instrument；全局结构在 Tracks/Live Loops 中另看。[入门](https://support.apple.com/en-gb/guide/garageband-iphone/chsff8c943/ios) · [屏幕键盘](https://support.apple.com/guide/garageband-iphone/play-the-keyboard-chs39282dbe/ios) | 手机一次只完成一种音乐活动，结构视图与输入视图分层。 |
| Ableton Note | Session 概览进入单个乐器/片段；可先演奏后 Capture，MIDI 精修同时提供拖动与固定步长操作。[产品](https://www.ableton.com/en/note/) · [手册](https://www.ableton.com/en/note/manual/) | 自由练习应先允许实验，再保存为一次操作；片段变体与确定性步进适合手机。 |
| FL Studio Mobile | Piano Roll 用点击插入、长按操作、框选和调式吸附，证明 Android 侧也形成触控优先的精修模式。[编辑器手册](https://www.image-line.com/fl-studio-learning/fl-studio-mobile-online-manual/html/plugins/FL%20Studio%20Mobile_Editors.htm) | 吸附、框选与上下文操作需要成为统一手势状态，而不是散落的特例。 |

归纳出的稳定模式是：精确拖动从来不是唯一途径；导航、选择、输入、精修必须有可见边界；
屏幕乐器是正式输入 adapter；功能完整不等于按钮同时可见。Apple 也要求 Pencil 交互即时反馈、
支持左右手且 hover 只做预览；Android 要求按当前窗口而非设备名响应 size class，并建议至少 48dp
触控目标。[Apple Pencil 指南](https://developer.apple.com/design/human-interface-guidelines/apple-pencil-and-scribble) ·
[Android 窗口尺寸](https://developer.android.com/develop/adaptive-apps/guides/use-window-size-classes) ·
[Android 触控目标](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)

## 3. 自适应信息架构

以实时窗口与输入能力为准，旋转、分屏或外接键盘后可重新排版，但保留稳定 selection、插入光标与活动。
建议以 `<600dp`、`600–839dp`、`≥840dp` 作为首轮宽度断点，再用高度和实机测试修正；断点是 UI
policy，不进入 session。

| 窗口 | 主结构 | 次级内容 |
|------|--------|----------|
| Expanded Pad | 顶栏 + 可折叠左工具轨 + 中央谱面 + 右检查器 | 可调高度的底部钢琴/属性/钢琴卷轴 |
| Medium Pad | 顶栏 + 谱面；工具轨压成横向上下文条 | 检查器与目录进入多档 bottom sheet |
| Compact Phone | 顶栏 + 单个焦点内容 + 底部活动栏 | 键盘、属性、候选进入半屏/全屏 sheet |

🚧 实施时增加统一的 `InputCapabilities(pointerKinds, hover, keyboard, stylus, viewportClass)`；
pointer 类型必须逐事件判断，不能因为设备支持 Pencil 就禁用手指钢琴。

### 3.1 Pad：完整能力、弹性工作区

- 横屏中央保持 continuous/paginated 谱面；左侧按“音符、结构、表情、几何”折叠，右侧只显示当前
  选择的属性/分析。顶栏常驻文档、undo/redo、播放、命令搜索。
- 竖屏或分屏不删除命令：左工具轨变上下文条，检查器与高级工具进入 sheet；系统分别记忆横竖布局。
- 底部虚拟钢琴可隐藏、半高或展开，不覆盖插入位置；外接键盘/MIDI 时可自动收起但不替用户切模式。
- 自由练习横屏保持时间轴 + 谱面主列和右侧和声/教学检查器；竖屏以活动切换和 sheet 呈现同一能力。
- “完整”指全部共享记谱、结构、表情、几何、播放、分析与自由练习能力可达。多窗口、JVM 插件等
  平台能力提供等价入口或在能力矩阵写明原因，不能静默消失。

### 3.2 手机：一个活动占据主屏

主谱面底部一级活动为 `记录 / 编辑 / 分析 / 试听`：

| 活动 | 主内容 | 常驻动作 |
|------|--------|----------|
| 记录 | 焦点谱段 + 插入光标 + 最小记谱条 + 钢琴 | 时值、附点、休止、变音、延音线、声部 |
| 编辑 | 当前选择 + 大手柄/属性 | 删除、复制、移调、语义 nudge、更多 |
| 分析 | 谱面锚点 + finding/规则摘要 | 严重度筛选、上一条/下一条、解释 |
| 试听 | 当前范围 + 跟随播放 | 播放、循环、速度、独奏/静音 |

顶栏常驻返回、标题/保存状态、undo/redo 和播放状态；低频的页面设置、导出与文档信息进溢出菜单。
切换活动先取消未提交 preview，不改变 revision，也不重建 session。

## 4. 主打谱交互

### 4.1 输入方式

| 输入 | 导航 | 选择与修改 | 音符输入 |
|------|------|------------|----------|
| 触控笔 | 两指触摸平移/缩放，笔默认不平移 | 精确命中，可直接拖；hover 显示候选 | 点谱面显示 ghost，抬笔提交；首版不做手写识别 |
| 手指 | 空白单指平移，双指缩放 | 第一次只选中，第二次拖外扩手柄 | 默认用虚拟钢琴步进；直接落音是显式工具 |
| 鼠标/键盘 | 桌面语义 | hover、快捷键、Shift 多选 | 现有键盘/MIDI 路径 |
| 辅助技术 | 语义滚动/跳转 | 上移、下移、提前、延后等 action | 可访问钢琴或结构化音高选择器 |

笔侧键/双击默认在“选择 ↔ 输入”间切换，也允许改成套索；左右手可调工具轨、放大镜和 sheet 对齐。
笔进入谱面感应范围后可以抑制掌根触摸，但钢琴区域仍接收另一只手。

### 4.2 命中与选择

- 乐谱字形保持排版尺寸；adapter 对手指扩大屏幕命中 halo，控制与透明命中区至少 48dp，触控笔使用
  较小半径。候选按“已选手柄 → 精确命中 → 当前工具相关类型 → 距离”排序。
- 候选分数接近时不静默猜测：Pad 显示锚点 callout，手机显示紧凑候选 sheet，例如“C♯5 音头”、
  “渐强线终点”、“第 12 小节线”。确认后只把稳定 ID/音乐坐标送入 intent。
- 手指第一次点击只选择。音符选中后，整个高亮/外扩区域可作为纵向音高拖动代理；休止符、文字使用
  位置柄；slur、hairpin、8va、房子等使用起点/终点/中点语义手柄。
- Pad 提供显式套索与“追加选择”；手机首版提供显式多选后逐项点击。小节/谱表/系统粒度应有可见控件，
  双击、三击或长按只能作为快捷方式。

### 4.3 拖动状态与提交

```text
Browsing ──tap──> Selected ──drag handle──> Previewing
    ^                |                            |
    | pan/pinch      | properties / nudge        | up: one intent
    |                v                            v
    +──────────── Browsing <──fail/stale── CommitPending
                              success ──> Selected(new frame.selection)
```

- 拖动期间把放大镜、目标标签、拍位/音高读数放在手指上方；按有效音高/拍位跨越给予轻触反馈。
- 根据元素锁定主轴并显示吸附；靠近视口边缘自动滚动。第二个 pointer、系统手势、切后台、活动切换或
  authoritative revision 变化都 cancel，不隐式提交。
- drag move 只更新本地/共享 preview，不入历史；pointer up 发送一次普通 intent，恰好形成一个历史项。
  提交后保留 preview，直到完整新 `RenderResult` 发布；不能收到流式第一页就提前解除保护。
- 每个拖动都有按钮或表单等价项；复杂的跨系统几何编辑在手机上可要求进入横屏精修页。

### 4.4 虚拟钢琴与步进输入

- 用户先在谱面确定 `staff/time/voice` 光标，再按琴键；单音发送 `InsertNote(midiNote=...)`，音名拼写、
  复音限制和跨小节规则由共享层处理。成功后只跟随 session 返回的 `nextInputPosition`。
- 钢琴上方只放时值、附点、休止、变音、延音线、声部和“更多”；支持换八度、中央 C、音名开关、
  可调键宽和试听。调式高亮可以减少失误，但不能隐藏半音键或改写实际输入。
- outside note-input 时按键只 audition；进入 input 后才写谱。外接 MIDI 与屏幕键盘生成同一种输入 intent。
- 和弦采用显式 latch：多键先进入本地 MIDI 集合，点击“写入和弦”或全部抬起后一次提交。
  🚧 当前 `InsertChord` 接受 `List<Pitch>`；实现前应给共享协议增加 MIDI chord 输入，由 session 统一拼写，
  不能在移动壳调用自己的 `Pitch.fromMidi` 规则。
- 连音组等一次性输入只消费 `noteInputTransition`，移动 UI 不自行计算下一个成员的时值或位置。

## 5. 手机的“总览 + 聚焦编辑”

- **总览**负责读谱、播放、finding 高亮和跳转，不允许在缩小的全谱上精确拖动。
- 点击小节进入**聚焦编辑**：显示当前系统，或当前小节前后的 1–2 个谱表组；顶部面包屑显示
  `小节 · 乐器/谱表 · 声部`，左右滑/按钮移动到相邻窗口。
- 小型作品默认整系统；钢琴谱默认大谱表；交响缩谱默认聚焦用户选中的乐器组或 finding 涉及的谱表，
  总览仍保留完整上下文。首版只裁切现有 render geometry，不在 UI 私自隐藏/重排谱表。
- 旋转与活动切换保留稳定 selection、插入光标、播放范围和缩放锚点；横屏优先提供较宽的精修窗口。
- 新建提供“快速草稿”，用最近/默认模板直接进入记录；保存、自动恢复与桌面共用 `.mecon`，不能产生
  手机专属简化数据模型。

| 能力 | Pad 目标 | 手机首版 |
|------|----------|----------|
| 打开/保存/恢复、播放、基础分析 | 完整 | 完整 |
| 音符/休止/和弦、常用结构与表情 | 完整 | 常用集合，其他可查看 |
| 范围符号、几何精修、多选/批量操作 | 完整 | 属性/nudge 为主，高级项后置 |
| 分页、打印、复杂页面与编制管理 | 平台等价完整 | 查看兼容，编辑后置 |
| 大型总谱 | 完整，按性能门禁 | 总览、谱表组聚焦、分析与小范围编辑 |
| 动态插件、多窗口 | 能力矩阵逐项说明 | 不进入首版 |

手机后置的是入口，不是文件兼容性。遇到不能编辑的已存在元素，应保持原样、允许选择/解释，并明确提示
可在 Pad/Desktop 完成；绝不能在保存时丢失。

## 6. 自由练习

### 6.1 Pad

横屏沿用[工作台交互基线](../exploration/free-practice-workbench-interaction-v2.md)的时间对齐关系：主列显示
时间轴 + 谱面，右栏显示和声、调性、惯用进行、教学与 finding；下方输入面板按活动切钢琴/属性。
竖屏改成 `和声 / 记谱 / 检查 / 试听` 活动和 sheet，但全部消费同一 `FreePracticeFrame`。

Pad 最终应覆盖桌面自由练习的领域能力。钢琴卷轴目前仍是 Desktop-only：纳入 Pad 前必须先把其持久化
编辑迁入 `FreePracticeIntent`/共享会话并补 trace，禁止复制桌面旁路；完成前在能力矩阵显式标 🚧。

### 6.2 手机

| 活动 | 焦点界面 | 关键交互 |
|------|----------|----------|
| 和声 | 横向槽位 filmstrip + 当前和弦/调性 | 点选槽；大边界柄调范围；目录/低音/惯用进行进 sheet |
| 记谱 | 聚焦谱段 + 最小工具条 + 钢琴 | 普通编辑包为 `FreePracticeIntent.Score(inner)` |
| 检查 | finding 列表 + 规则说明 | 点 finding 跳至谱面锚点并固定高亮 |
| 试听 | 当前范围 + 写作状态/候选 | 播放、取消、换一个；区分无解与预算耗尽 |

- 活动切换不重建 session；`FreePracticeSelection` 让槽位、调性线、惯用进行和谱面选区保持同步。
- 时间轴默认精简为卡片；完整多轨进入横屏/全屏。命中、量化、preview/commit 仍委托
  `FreePracticeTimelineController`，缺少语义 nudge 时扩 common controller，不在 Compose 计算 `Fraction`。
- 自动写作运行时保留上一幅可用谱面并标出目标范围；显示不确定进度和取消，不伪造百分比。
  `Solved` 提供试听/换一个；`NoSolution` 保留选择；`BudgetExhausted` 给缩小范围/重试。
- 后台结果必须回到 session 校验 requestId、revision 与 fingerprint；异常调用对应
  `applyBackgroundFailure` / teaching / finding failure 通道，回退到最后提交状态，不能永久停在 `RUNNING`。
- 🚧 “捕获刚才演奏”和“一键复制为变体”适合手机实验，但它们是新的共享领域能力：需先定义滚动缓冲、
  量化 preview、原子 commit 与 trace，不能作为平台本地捷径。

## 7. 共享架构与状态归属

```text
Mobile shell / pointer / MIDI / audio / files
  ├─ transient UI: activity, sheet, zoom, loupe, handles, preview
  ├─ ScoreEditIntent ───────────────> ScoreEditingSession
  └─ FreePracticeIntent(.Score) ────> FreePracticeSession
                                          │
                    Storage → Runtime → Computed → Render Geometry
```

- 平台 adapter 可做候选过滤、坐标映射、触觉和瞬时 preview；像素、数组下标、帧内对象引用不得进 intent。
- Renderer 只排版，不因手机焦点模式生成/删除音乐元素。若要只重排选定谱表组，应增加 common 的只读
  render profile，并验证映射；不能修改文档的谱表可见性冒充视图过滤。
- undo/redo 常驻；preview、hover、sheet、缩放不入历史。undo/redo 后直接采用 session 恢复的 selection。
- 自动恢复只在 `documentChanged` 为真时防抖写入；selection、catalog、finding 更新不反复打包 `.mecon`。
- 大谱的 Compute/Layout/Render、播放时间线与全事件扫描不在 UI 线程；只处理 viewport/tiles，新完整帧
  到达前保留旧不可变帧。详见[大乐谱性能守则](../performance/large-score-editing.md)。

## 8. 无障碍与反馈

- Canvas 只为可见视口建立语义节点，按时间、谱表、声部排序并用稳定 ID 保持焦点；标签包含小节、拍位、
  乐器/谱表、声部、音高、时值和选中状态。
- 为选择、删除、上下移、提前/延后、延长/缩短、跳转小节/谱表/finding 提供语义 action；关键能力不只
  依赖拖动、颜色、触觉或自定义多指手势。
- finding 可朗读并跳到对应音符；钢琴键标注音名、八度和按下状态；声部颜色同时显示数字/文字。
- UI 文字支持动态字号，乐谱缩放独立；支持高对比、减少动态、关闭触觉和左右手布局。
- SMuFL 仍用 Bravura、命名码位和 Canvas 基线测量，不能因移动端改用普通 `Text` 猜测居中。

## 9. 实施阶段与验收

| 阶段 | 交付 | 退出条件 |
|------|------|----------|
| M0 基础 | Android/iOS target、进程内 session、字体/绘制、文件/音频、viewport cache | 同一 score/practice trace 在移动 target 通过；移动 `.mecon` 可由桌面回读 |
| M1 Pad 核心 | 笔、手指两步选择、候选、放大镜、手柄、钢琴单音步进 | 一次拖动一个历史项；取消零历史；undo 恢复选择 |
| M2 Pad 完整 | 全部共享记谱/自由练习入口、硬件键盘/MIDI、协议化钢琴卷轴 | Pad 能力矩阵逐项有实现或平台原因 |
| M3 手机记录 | 快速草稿、聚焦谱面、钢琴、常用编辑、播放/恢复 | 单手完成新建—录入—修改—试听—保存 |
| M4 手机练习/分析 | 四活动、自动写作状态、finding、缩谱导航 | 完成选和弦—配声—检查—换结果—试听；后台崩溃可恢复 |

除 JVM/JS 现有 trace 外，新增移动 adapter/设备测试：手指歧义命中、笔 hover 与无 hover、两步拖动、
边缘滚动、pointer cancel、`nextInputPosition`、`noteInputTransition`、活动/旋转保留选择、worker 崩溃、
屏幕阅读器非拖动路径。大谱还要证明 UI 主线程无全谱扫描，完整结果发布前不提前解锁。

