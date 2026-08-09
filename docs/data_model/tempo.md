# 速度关键帧

## 1. 核心模型

`StorageGlobalTrack.tempoEvents` 是按 `onset` 排序的全局速度关键帧。每个
`StorageTempoEvent` 同时描述播放速度、谱面显示以及到下一关键帧的过渡：

- `bpm`：兜底的四分音符 BPM；绝对关键帧直接使用它。
- `referenceEventId` / `referenceRatio`：可选的来源关键帧关系。有效速度为来源有效速度乘倍率；
  引用损坏或成环时回退到 `bpm`。
- `displayStyle`：节拍器、文字、文字与节拍器、等式、渐变文字线或隐藏。
- `text`：`Allegro`、`più mosso`、`a tempo`、`Tempo I`、`rit.` 等显示文字。
- `beatUnit` / `equivalentBeatUnit`：节拍器左侧单位，以及等式右侧单位。
- `transitionToNext`：当前关键帧到下一个关键帧的播放插值方式。

`bpm` 统一使用四分音符 BPM，避免不同显示单位污染播放语义。例如二分音符 = 60
保存为 `bpm = 120`、`beatUnit = HALF`；渲染时换算回显示值 60。

## 2. 引用与预设

- `a tempo`：引用本轮渐变开始前的关键帧，倍率 1。
- `Tempo I`：引用乐曲开头关键帧，倍率 1。
- `più mosso` / `meno mosso`：引用前一关键帧，默认倍率 1.15 / 0.85；属性面板修改
  有效 BPM 时更新倍率，保留引用关系。
- 节拍单位等式：引用前一关键帧，倍率为右单位与左单位相对四分音符的比例。

修改来源关键帧后，Computed 与播放转换会重新解析全部引用，因此后续恢复速度记号自动联动。

## 3. 渐快、渐慢和曲线

拖动输入渐快/渐慢会创建两个关键帧：起点显示 `accel.` / `rit.` 并把
`transitionToNext` 设为曲线过渡；终点默认隐藏并保存目标 BPM。终点通过关键帧顺序解析，
不额外保存易失效的结束 TimeCode。

渲染层仅把 `GRADUAL_TEXT` 视为有结束点的区间附件并允许跨系统拆分；其他显示样式即使
存在 `nextTime`，也仍是所属 TimeCode 上的点记号，不会在后续系统开头重复渲染。

支持 `STEP`、`LINEAR`、`EASE_IN`、`EASE_OUT`、`EASE_IN_OUT`。MIDI 导出把连续曲线
采样为有限的 tempo meta events；阶跃只生成关键帧事件。

## 4. 开头关键帧与编辑标记

乐曲开头 `(measure=1, beat=0)` 始终有有效关键帧。新乐谱直接存储它；旧乐谱若缺失，
Runtime/Computed 和播放层使用 `defaultTempo` 合成兼容关键帧，首次速度编辑时写回。

隐藏关键帧不进入正式谱面；编辑模式以蓝色小圆点显示并可选中，预览/参考视图不显示。
用户可在属性面板切换为节拍器样式，使其成为正式速度记号。
