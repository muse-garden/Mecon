# 演奏法、延长停顿、力度与八度记号编辑

桌面编辑器通过 `ExpressionEditEngine` 修改演奏法与谱表附着记号。音乐语义在
Storage/Runtime/Computed 层决定，Renderer 只消费已有的 `ComputedStaffAttachment` 并排版。

## 调色板

- 音符调色板最下方为演奏法；按钮可多选，录入时写入新音符，存在音符选区时批量切换。
- fermata 已从演奏法面板移除。其他元素调色板新增“延长与停顿”，提供五种 fermata、
  单声部/单谱表/全谱 breath scope 与四种 breath glyph；其后才是力度与八度记号。
- 新建力度、hairpin、`cresc.`/`dim.`、8va/8vb 均使用 staff scope，`voiceNumber = null`。
- 力度按钮使用 Bravura 的 SMuFL 字形，覆盖 `dynamicPPPPPP` 至 `dynamicFFFFFF`、`dynamicNiente`、
  `mp/mf/pf/fp`、`sf/sfp/sfz/sfpp/sfzp/sffz`、`fz/rf/rfz` 等全部已定义的 SMuFL 力度字形；
  楔形 hairpin 使用 `dynamicCrescendoHairpin` / `dynamicDiminuendoHairpin`。`cresc.`/`dim.` 与
  `8va`/`8vb` 使用斜体衬线文字，按钮尺寸与谱号面板统一。

## 添加

- fermata 和 breath 的存储锚点均为记号后方 TimeCode。选中音符或直接点击符头添加 fermata
  时使用该音符结束时间；fermata 一次写入 global track 并作用到全部声部。
- breath 的自动 X 位置取前一音与后续音时间列的中点；跨系统时放在新系统后续音之前，不覆盖符头。
- breath 的点选、虚线指引和拖动统一使用边界候选：实际小节线或相邻音符列中点，而不是符头位置。
  单声部、单谱表与全谱 breath 均可拖动并重新吸附；全谱 breath 改变时间时联动所有谱表。
- 点力度：选择音符后按 `(staff, TimeCode)` 去重批量添加；无选区时点击按钮进入点选模式。
- 区间记号：选择音符后按谱表分别取最左、最右 onset；无选区时拖动画出区间。
- 无选区拖动区间记号时，拖动过程中使用同一套 `HairpinGeometry` /
  `IntervalAttachmentGeometry` 绘制灰色 ghost，实时显示当前起止范围；提交成功后自动退出对应添加模式。
- 画布定位先以系统内谱表 Y band 决定 staff，再用相邻事件 X 的中点作为 TimeCode 分隔线。
- 选中 staff attachment 时显示到其 TimeCode 对应符头的编辑器虚线；同一时刻有多个声部或和弦符头时，
  只在记号所属系统与谱表内选择离端点最近的符头，禁止跨行吸附。虚线不进入 Renderer 输出或空间索引。
- 点力度可拖动整体；hairpin 可拖动主体整体平移，也可单独拖动左右端点；八度记号只允许拖动端点。
  主体平移时两端分别按事件 X 中点吸附，并可整体切换到谱表上方或下方。拖动写入 anchor-relative `AttachmentGeometry`，
  wedge 的两端 Y 独立（可倾斜），文字渐强渐弱与八度线保持水平。
- 拖动固定使用起始谱表与系统，即使指针越过谱表上下边界，也继续按事件 X 中点重新吸附端点 TimeCode；
  安全带把记号保持在五线与同系统音符包围盒之外，
  跨过中线时可跳到谱表另一侧。松手后音乐锚点与几何在同一 undo 事务提交。
- 拖动过程中隐藏旧记号，使用临时几何实时绘制记号及其符头连接线；松手后才提交并重新排版。

## 八度语义

Storage 中保存实际发声音高。添加 8va/8vb 的同一编辑事务会把区间内该谱表所有声部的
实际音高升/降八度，Computed 的书写谱位补偿使音符在页面上保持原位置。删除记号执行逆变换。

复制音符始终复制实际音高，因此从八度记号内粘贴到外部（或反向）不会改变听到的音高。
只有八度区间内全部非休止音符均进入复制选区时，八度记号才进入剪贴板。

## 选择与剪贴板

- 演奏法、fermata、breath、点力度、hairpin、文字渐强渐弱和八度记号均注册 `EventSection`，可点击或框选。
- 装饰音位于“其他谱表元素”插入面板。trill、各类巴洛克 mordent、turn 共用谱表附件选择与属性
  管线；turn 的同一按钮在点击符头时创建音上 turn，在点击相邻音符边界时创建音间 turn。
  区间 trill 用拖拽创建，波浪延长线进入 `StaffAttachmentLayoutComputer`，参与跨系统切段和碰撞。
- 装饰音属性可修改上下辅助音变音、元素时值、mordent 往返次数及 trill 播放模式。未覆盖的
  辅助音按当前调号取调内相邻音；显式覆盖会在记号上/下显示对应变音记号。
- 琶音按钮作用于单个和弦，可选择普通、向上、向下或 non-arpeggiate；Renderer 只消费
  Computed 音符携带的琶音类型并按和弦音域排版。
- 选中 fermata 或任一 breath 时，属性面板可用 `分子/分母` 编辑延长/休止拍数。
  也可直接输入整数；例如 `1` 与 `1/1` 都表示一个四分音符拍。
- 属性文本框编辑期间窗口级快捷键暂停；数值仅在 Enter 或失焦时校验并提交，未完成的中间文本
  不触发模型更新。窗口级门控由公共 `meconTextInputFocus` 提供，`MeconTextField` 与保留自定义
  样式的 `BasicTextField` / `OutlinedTextField` 均使用它。
- fermata 与全谱 breath 不可剪切、复制、粘贴；删除按共同 global event ID 联动全谱。
  单声部/单谱表 breath 随所选音符进入剪贴板，并在粘贴时保持 scope。
- 点力度按 `(staff, TimeCode, level)` 去重。
- hairpin 与文字渐强渐弱复制时同音符选择范围相交，并在剪贴板中截取到该范围。
- 音符与表达记号使用一次联合粘贴提交，保持单个 undo 历史状态。

相关实现：

- `core/.../edit/ExpressionEditEngine.kt`
- `apps/desktop/.../ui/components/lefttoolbar/NotePalette.kt`
- `apps/desktop/.../ui/components/lefttoolbar/ScoreElementPalette.kt`
- `apps/desktop/.../ui/views/RenderedScoreView.kt`
