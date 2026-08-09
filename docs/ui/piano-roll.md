# 钢琴卷轴视图 (Piano Roll)

> 路径：`apps/desktop/src/main/kotlin/com/mecon/desktop/ui/views/PianoRollView.kt`
> 状态：`ui/views/pianoroll/PianoRollState.kt`

## 1. 功能概览

与五线谱互补的可视化视图，直观显示音高、时值与密度关系。

- **方向切换**：`HORIZONTAL`（音符向左流动）/ `VERTICAL`（音符向下掉落）
- **平移**：鼠标拖拽，范围约束在 MIDI 0–127 音域内
- **独立缩放**：滚轮缩放，X / Y 轴比例独立管理
- **播放联动**：Playhead 跟踪 `AudioEngine.currentPositionTicks`，播放时自动滚动时间轴
- **停靠切换**：可在主乐谱下方（上下分割）或右侧（左右分割）显示，两种位置分别记忆尺寸
- **双向选择联动**：乐谱选区高亮对应卷轴矩形；点击卷轴音符反向产生同一
  `EventSection` 选区。音符命中优先于空白网格点击，该能力属于通用 `PianoRollView`
  交互契约，主编辑器与自由练习共用
- **分析叠加**：和弦分析可打开和弦符号栏与和弦音背景；复调助手开启时在音符内显示音级

## 2. 渲染架构

```
RuntimeScore
  ├── ScoreToMidiConverter → PianoRollNote（播放权威时间）
ComputedScore
  ├── 和弦事件 → PianoRollChordSpan
  └── 调性区域 → 音级文字
        └── PianoRollFrame（后台不可变帧）→ Canvas 绘制
```

不复用五线谱的 `RenderEngine`，是独立绘制路径。

和弦模式在可见音域中为每个和弦成员（根音、三音、五音、扩展音）绘制不同的半透明行背景，
即使该音高当前没有乐谱音符也会显示；水平卷轴顶部另有与时间区段对齐的和弦符号栏。

时间轴 tick 与 `AudioEngine` 的 `DEFAULT_TICKS_PER_QUARTER = 1024` 对齐，保证播放进度精确同步。

乐谱选择使用 `VoiceEvent → PitchEvent` 的运行时引用关系映射到 MIDI 矩形；不能假设两类事件
ID 相同。单音头选择还需同时匹配该音头的 MIDI 音高。

## 3. 键盘布局 (KeyboardLayout)

左侧（或顶部）绘制键盘，通过 `KeyboardLayout` 接口抽象，支持三种布局：

| 布局 | 说明 |
|------|------|
| `NormalPianoLayout` | 标准钢琴（7 白键 + 5 黑键/八度），含阴影与堆叠效果 |
| `ChromaticLayout` | 12 个半音等距平铺（全视为白键），适合弦乐器用户 |
| `CustomScaleLayout` | 传入 `BooleanArray(12)` 自定义黑白键，可表示任意调式（全音阶、梅西安调式等） |

实现原则：先计算当前八度内"白键"数量平均分配视觉空间，再在中间叠绘"黑键"。

## 4. 状态 (PianoRollState)

```kotlin
class PianoRollState {
    var orientation: Orientation
    var xScale:      Float
    var yScale:      Float
    var viewportOffset: Offset
    val keyboardLayout: KeyboardLayout
}
```

主界面的 `PianoRollView` 默认只读，视图操作仅更新 `PianoRollState`，不影响
`RuntimeScore`。内部绘制与坐标能力拆为共享 `PianoRollSurface`；需要编辑的工作区通过
非空 `PianoRollInteractionConfig` 添加选择、插入和修改，而不复制 Canvas。

播放跟随由 `PianoRollView` 的 UI 状态控制：`AudioEngine` 每 50ms 发布一次位置，视图在相邻位置之间线性插值，并用同一个插值值计算播放线与时间轴偏移，避免二者分帧更新造成播放线左右摆动。水平布局沿 X、垂直布局沿 Y 滚动，让播放头稳定在键盘之后可用视口约三分之一处。手动拖拽仅在手势期间暂停跟随，松开或取消后恢复自动滚动。

`PianoRollView` 以引用 identity 监听 `RuntimeScore` / `ComputedScore`。MIDI 转换、全谱音符配对、
小节网格、和弦区段和每个音符的调性音级都在 `produceState + Dispatchers.Default` 中经过
50ms 可取消防抖后构建；Compose 主线程只投影当前小规模选择并绘制最后完成的不可变帧。
新帧完成前保留旧帧，禁止把上述全谱工作移入 composable、`remember(score)` 或指针回调。
右侧窄停靠时，音级与和弦文字必须先和实际 Canvas 视口求交；可见宽高不足时跳过文字，
不能把视口外的 `topLeft` 直接传给 `DrawScope.drawText`，否则 Compose 会生成负宽度约束。

编辑命中使用随不可变帧构建的时间/音高索引。指针回调只做屏幕坐标反变换和可见区查询；
量化、拼写、活动声部和同起点处理由共享编辑宿主决定。钢琴卷轴不得自行保存第二份音符，
也不得硬编码罗马数字到 pitch class 的映射。

## 5. 扩展键盘布局

```kotlin
class MyCustomLayout : KeyboardLayout {
    override val keysPerOctave: Int = 12
    override fun isBlackKey(semitone: Int): Boolean = ...
}
```

传给 `PianoRollView` 即可即时切换键盘外观。

## 6. 公共键盘组件

钢琴卷轴不再自行绘制键盘。`PianoRollView` 只负责音符、网格和播放线，
键盘由独立仓库 `mecon-components` 中的 `PianoRollKeyboard` 绘制。
Rhody 的可演奏键盘也依赖同一仓库中的 `Keyboard`，两种场景共用调色板与
琴键材质绘制代码。两个应用通过 Gradle composite build 直接编译该仓库，
无需复制源码或手工同步。

## 外部和声投影

嵌入式编辑器可提供投影后的 `PianoRollChordSpan`。调用方只把 typed 和声状态转换为时间段、
标签和成员 pitch class；标签、和弦音背景高亮、裁切和缩放仍由共享 `PianoRollSurface` 绘制。
