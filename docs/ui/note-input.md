# 键盘与 MIDI 音符输入

> 状态：P1–P4 已实现（2026-07-24），P5 的可视化逐键重绑与压力测试继续迭代。鼠标音符笔与编辑引擎现状见
> [score-editing.md](score-editing.md)，快捷键体系见 [settings.md](settings.md)，音频输出见
> [../audio/README.md](../audio/README.md)。

## 1. 目标与边界

提供两种共享输入源（电脑键盘、MIDI 键盘）和两种录入方式：

- **步进录入**：输入音高，时值取 `NoteToolState.note.duration`；每批音高写入后移动录入光标。
  根状态只管理互斥工具切换；音符、谱号调号拍号、表情记号和反复结构默认值分别由
  `toolstate/` 下的功能子状态持有。
- **实时录入**：记录 NoteOn/NoteOff 的音高与时间，经节拍时钟和量化生成音符、休止、连音及延音线。
- 两种方式均支持**绝对音高**与**调内相对音高**；默认相对音高。
- MIDI 的音高拼写由统一算法决定；电脑键盘的升/降行是用户明确拼写，优先于自动选择。

v1 不做自动声部分离、踏板记谱、力度/velocity 持久化、swing、人性化偏移或即兴生成。
这些输入事件仍应保留在可复用的采集层，避免未来即兴模块重新实现设备、时钟与量化。

当前持久化模型已经能表达和弦、休止、连音、连音组和分段延音，不新增 Storage 字段。
输入偏好写应用设置；录入光标、按键状态和未提交 take 都是会话态，不进入乐谱文件。

## 2. 必须补充的交互契约

原方案还需要明确以下边界：

1. **独立录入光标**：不能用普通选区代替，否则时值快捷键会切到“编辑选区”语义。
2. **原子和弦与串行提交**：当前 `Insertion` 一次只收一个音，异步连续调用可能读到同一旧状态；
   一批和弦必须作为一次编辑、一次撤销提交，快速输入则进入串行命令队列。
3. **书写音与实音**：内部 `Pitch` 是书写音，Computed 层再应用谱表移调得到 MIDI 音高。
   普通 MIDI 键盘默认发送实音，移调乐器录入时须先逆变换到书写音。
4. **实时和弦的不等长释放**：一个 `VoiceEvent` 只有一个公共时值，不能直接存不同长度的和弦音；
   必须按“当前仍按住的音集合”切段，并给持续音加延音线。
5. **输入焦点与按键重复**：文本框/对话框优先；只在乐谱输入会话激活时吞字母键；
   忽略系统 KeyDown 自动重复，并以 KeyUp 解除 held 状态。
6. **设备生命周期**：MIDI 热插拔、NoteOn velocity=0、丢失 NoteOff、All Notes Off、窗口/文档切换
   都要有确定行为，不能留下长音或把事件写到另一个文档。
7. **延迟校准**：量化使用单调高精度时钟，并允许按设备保存输入/输出延迟补偿；
   现有 50ms 播放头轮询不能作为录音时钟。
8. **覆写策略**：实时录入默认替换当前声部的 take 区间，保留其他声部；“叠加/overdub”
   作为显式选项，不能由时间重叠时临时猜测。

## 3. 模式与状态机

建议默认用可重绑定的 `I` 进入/退出音符输入会话；激活该键的 KeyUp 前禁止把同一次按键解释成音高。
`Esc` 退出步进录入；实时录音中 `Esc` 停止、提交并退出，丢弃 take 必须点明确按钮并确认。

```text
INACTIVE
  └─ I / 工具栏入口 → STEP_READY（默认）或 REALTIME_ARMED（保留上次子模式）

STEP_READY
  ├─ pitch batch / 0 → 提交并移动光标
  ├─ ←/→ → 移动光标；↑/↓ → 整体移八度
  └─ Esc → INACTIVE

REALTIME_ARMED
  ├─ 节拍器运行但不改乐谱
  ├─ 首个有效 NoteOn → RECORDING（该音也被采集）
  └─ Esc → INACTIVE

RECORDING
  ├─ NoteOn/NoteOff → 追加原始 take
  ├─ Space / Esc → 量化、一次提交、退出
  └─ 设备断开 → 闭合所有音、停止并提示
```

实时 armed 时建立稳定拍相并开始节拍器；首音相对该拍相量化，不牺牲首音来“触发”录音。
可选 1–2 小节 count-in，但默认不强制。录音中仍允许 MIDI 输入在应用窗口失焦时工作；
电脑键盘输入只在乐谱工作区持有焦点时工作。

## 4. 录入光标

```kotlin
data class NoteEntryCaret(
    val staffTrackId: TrackId,
    val voiceNumber: Int,
    val onset: TimeCode,
)
```

进入会话时按以下顺序确定起点：

1. 单个音符/休止或小节谱表被选中：取其谱表、onset（小节选择取小节首）；
2. 已有合法光标：沿用；
3. 否则取首个可见音高谱表、声部 1、第一小节起点。

步进录入规则：

- 一批新音按当前时值写入，成功后只前进一次。
- 光标落在已有非休止事件上时，忽略调板时值，按该事件原时值并入和弦，然后前进到下一事件；
  这样回溯后可以可靠叠加和弦。
- `←/→` 到当前声部前一个/后一个事件起点（包括显式休止）；没有事件时按当前时值移动并夹在乐谱内。
- `↑/↓` 只改变输入音域 `registerOffset ± 1`，不移动既有音符。
- `0` 立即插入当前时值的休止并前进；不能与待提交和弦混合。
- 同一批内按 MIDI 音高去重；等音异写冲突保留第一项并给出非阻塞提示。

光标移动和音域切换不进入撤销历史。每个单音/和弦/休止批次是一个撤销单元。

## 5. 电脑键盘映射

默认以主键区 `G` 为中心自然音。主行自然音从 `A` 到 `'` 共 11 级，覆盖十度：

| 键 | A | S | D | F | G | H | J | K | L | ; | ' |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 相对中心的调式音级偏移 | -4 | -3 | -2 | -1 | 0 | +1 | +2 | +3 | +4 | +5 | +6 |

利用物理交错，默认变音行定义为：

- 上排 `Q W E R T Y U I O P [`：基础级数 `-5..+5`，各升高半音；
  相对模式下 `Y=升1`，最右 `[` 为 `升6`。
- 下排 `Z X C V B N M , . /`：基础级数 `-3..+6`，各降低半音；
  相对模式下 `V=降1`，最右 `/` 为 `降7`。

因此默认中心附近 `T = 前一级♯`、`Y = 中心级♯`、`V = 中心级♭`、`B = 后一级♭`。
即使结果是 B♯、C♭ 等，它也是用户明确输入，不自动改成等音；这也是上下两行都有价值的原因。
该交错表已经评审确认；设置中仍提供可视化测试与逐键重绑。

两种音高模式：

- **绝对**：`G=C4`，主行依次为固定自然音；升/降行产生固定书写变音，与当前调号无关。
- **相对**：`G=当前位置调号的 1`，主行沿当前 mode 的音级循环；升/降行相对对应音级 ±1 半音。
  光标跨调号时即时更新。标准大小调完整支持；自定义音阶按 `KeySignature.scale()` 映射。

“自定义中心键”指 `anchorPhysicalKey`，v1 限主行自然键，默认 G；音区由上下箭头独立调整。
设置保存按键位置映射而非输入字符，并提供 QWERTY 默认配置，避免 AZERTY/Dvorak 与系统字符布局混淆。
消费级键盘可能受按键冲突/无全键无冲限制，软件只能组合实际收到的事件。

输入会话中的优先级：

1. `Ctrl/Alt` 组合的撤销、复制等全局命令；
2. `Esc`、箭头、音高字母和 `0`；
3. 现有数字时值、附点、延音线、连音组快捷键；
4. 退出输入会话后才恢复 `S/F/N` 等原有变音快捷键含义。

## 6. 和弦聚合

步进模式对键盘与 MIDI 使用同一个 `ChordCollector`：

- 首个 NoteOn 开启固定窗口，默认 60ms，可设 20–150ms；窗口从首音算起，不滑动，保证延迟有上限。
- 窗口内的 NoteOn 组成一个和弦；NoteOff 只更新 held 状态。
- 窗口关闭后产生一个不可变 `StepChordCommand` 并进入串行队列。
- 慢于窗口的琶音成为连续音；用户可左移光标再补音，或调大窗口。
- 键盘自动重复、同 source+key 的重复 NoteOn、仍 held 时的再次触发均忽略。

批量编辑 API 应接受 `List<Pitch>`，在一个 RuntimeScore 快照上完成“新建和弦/并入已有事件”，
再做一次 incremental compute 与一次 history commit；不要循环调用现有异步 `applyNoteEdit`。

## 7. MIDI 输入

桌面适配器用 `javax.sound.midi.MidiDevice`，规范化为：

```kotlin
sealed interface PerformanceInputEvent {
    val sourceId: String
    val atNanos: Long
    data class NoteOn(val midi: Int, val velocity: Int, ...) : PerformanceInputEvent
    data class NoteOff(val midi: Int, ...) : PerformanceInputEvent
    data class ControlChange(val controller: Int, val value: Int, ...) : PerformanceInputEvent
}
```

设置包括设备、channel（全部或单通道）、velocity 下限、MIDI thru、绝对/相对、设备中心音
（默认 MIDI 60）、和弦窗口与延迟补偿。设备标识优先稳定 ID，缺失时按 vendor/name 回退并要求确认。

- **绝对 MIDI**：设备音高视为实音（默认），按目标谱表移调的逆变换得到书写音；
  可选“设备发送书写音”以跳过此步。
- **相对 MIDI**：设备中心音映射到当前书写调的主音所对应实音，其余键保留半音距离，再逆变换为书写音。
- NoteOn velocity=0 归一为 NoteOff；CC123/120 与断开均关闭所有 active notes。
- CC64 默认只影响 MIDI thru 试听，不延长记谱时值；控制器轨落地后再提供踏板记谱。
- 低延迟 thru 需要成对 `noteOn/noteOff` 的 `LiveNoteSink`，不能复用当前固定时长 `audition()`。

## 8. 自动音高拼写与临时记号

输入解析器只决定 `Pitch(diatonicSteps, chromaticOffset)`；是否实际绘制 ♯/♭/♮ 仍由
Computed 层的临时记号逻辑统一决定，Renderer 不生成临时记号。

电脑键盘携带 `NATURAL/RAISE/LOWER` 强提示，直接保留用户选择。MIDI 没有拼写信息，针对目标
书写 MIDI 音高枚举 `chromaticOffset ∈ -2..2` 的候选，并以确定性代价选择：

1. 匹配当前位置调号/调式音级，减少相对调号的偏离；
2. 减少双升双降与不必要的还原号；
3. 与同小节同谱位已生效的临时记号一致，避免来回切换；
4. 旋律上下文优先合理的自然音级运动，惩罚难读的增减音程；
5. 同起点和弦优先三度堆叠及同音级角色一致；
6. 平局时按调号方向（升号调偏升、降号调偏降）和固定枚举顺序裁决。

实时录入先用局部贪心结果显示预览；take 停止后可在**本次 take 内**用动态规划复核相邻拼写。
不得自动改写 take 外既有音符或用户明确输入的升/降拼写。写入后正常增量重算整小节临时记号，
因此较早插入的音会自动调整后续记号的显示，无需编辑 Render 元素。

## 9. 实时时间、量化与记谱化

使用单调 `PerformanceClock`，从当前位置 `TempoMap` 建立 `nanos ↔ TimeCode` 双向映射；
速度变化必须积分映射，不能假定整段固定 BPM。节拍器按 `TimeSignature.beatGroups` 重音，
用提前调度的音频时钟输出，不依赖 UI 定时器。

量化配置由“最小直拍粒度 + 允许的连音细分”组成，连音默认可选 2、3、6：

1. 对补偿延迟后的 NoteOn/NoteOff 分别找最近候选边界；
2. 直拍与连音边界同距时优先直拍，避免无意义连音；
3. release 不得早于 onset，过短音提升到一个最小格；
4. 二连音只在复拍 beat group 等能表达 `2:3` 的上下文候选；三/六连音按 beat group 生成；
5. 跨小节长音在边界切开并加延音线。

量化后按所有 onset/release 边界扫描“当前按住音集合”：集合不变则合并区间，集合变化则生成新段，
持续到下一段的音加 tie。之后用 `DurationDecomposer` 与 `TupletSupport` 生成普通时值、休止和
`TupletSpan`。这能正确表达“和弦中某音先松开”，但 v1 始终写入一个活动声部，不猜左右手声部。

实时 take 先存内存并显示轻量预览，停止时才原子写入乐谱，整个 take 为一个撤销单元。
默认清除活动声部的 take 时间区间后写入；overdub 只在用户显式开启时合并同 onset 的兼容事件，
不兼容时给出冲突预览而非静默覆盖。渲染落后时继续缓存输入，不阻塞设备线程。

## 10. UI 与分层

活动谱表上显示非持久化 HUD：`步进/实时 · 相对/绝对 · G=E4 · 范围 A3–F5`，实时模式再显示
设备、量化、armed/recording。光标画竖线并高亮当前声部；调号或音区改变时 HUD 即时更新。
HUD 是 desktop score-view overlay，只借 RenderResult 定位，不进入 Computed/Renderer 元素生成管线，
也不参与谱表 extra extent 或导出。

建议模块归属：

```text
:performance-input（新 KMP 模块，依赖 :api）
  规范化事件、ChordCollector、PerformanceClock、Quantizer、PitchSpeller、take 模型
:core
  ChordInsertion / CaptureMaterializer：不可变 RuntimeScore 批量编辑
apps/desktop
  Compose 键盘适配、JvmMidiInputService、NoteInputController、HUD 与设置
:audio
  MetronomeSink / LiveNoteSink 的低延迟输出实现
ScoreSession
  串行输入队列、批量增量计算、录音 transaction 与单次撤销
```

## 11. 实施顺序与验收

1. **P1 步进键盘**：光标、模式优先级、映射/HUD、原子和弦、串行提交。
2. **P2 步进 MIDI**：设备管理、实音/书写音转换、自动拼写、低延迟 thru。
3. **P3 实时基础**：高精度时钟、节拍器、直拍量化、take 事务。
4. **P4 连音与复音时值**：2/3/6 连音、held-set 切段、跨小节 tie、延迟校准。
5. **P5 完善**：overdub、热插拔恢复、可视化键位编辑与压力测试。

必须覆盖的测试：

- C/F♯/E♭ 大小调与中途换调的绝对/相对映射，键盘明确升降不被改写；
- 移调谱表 MIDI 实音往返后 computed MIDI 与设备输入一致；
- 和弦窗口边界、KeyDown 重复、重复音与异步快速输入不丢事件；
- 已有事件回溯叠加保持原时值，整批一次撤销；
- 4/4、6/8、变速段的直拍及 2/3/6 连音量化；
- 不等长和弦释放生成分段 tie，跨小节闭合正确；
- velocity=0、CC64、All Notes Off、设备断开和文档切换无悬挂音；
- 文本框/对话框不被截获，输入模式内 `S/F/N` 不误触旧快捷键。

## 12. 已确认的实现默认值

- 使用上述完整交错键表：上排到 `[`（升6），下排到 `/`（降7）；
- `I` 进入输入会话，实时/步进由 HUD 切换并记住上次选择；
- MIDI 在移调乐器上默认“设备发送实音”；
- 实时 armed 时节拍器先运行，首音被保留并启动 take；
- 实时默认替换活动声部区间，停止后整 take 一次撤销。

## 13. 实现位置

- `performance-input/`：键位映射、和弦窗口、MIDI 音高解析、变速积分时钟、2/3/6 连音候选、
  held-set 切段和原始 take；
- `core/.../edit/NoteInsertion.kt`、`CaptureMaterializer.kt`：原子和弦与整段录音落地，
  包括逐音延音线和活动声部区间替换；
- `apps/desktop/.../input/`：键盘会话状态机、MIDI 热插拔、串行提交、实时控制器；
- `audio/.../LiveNoteSink.kt`、`MetronomeSink.kt`：成对 MIDI thru 与独立打击乐节拍器；
- `NoteInputHud.kt`：模式、中心音、范围、设备、量化粒度与录音状态。

当前 HUD 可循环选择主行中心键和直拍量化粒度；完整的逐键可视化重绑界面属于 P5。
