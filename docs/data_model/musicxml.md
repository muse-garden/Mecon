# MusicXML 互操作

> 模块：`core/src/commonMain/kotlin/com/mecon/core/musicxml/`
>
> **状态**：✅ 已实现（4.0 Partwise 双向）

## 1. 入口 API

```kotlin
import com.mecon.core.musicxml.MusicXmlConverter

val score: StorageScore = MusicXmlConverter.import(xmlString).getOrThrow()
val xml:   String       = MusicXmlConverter.export(score).getOrThrow()
val isMxl: Boolean      = MusicXmlConverter.isMusicXml(content)
```

桌面应用通过 `ScoreFileService.loadAuto / saveAuto` 按扩展名分派：

| 扩展名 | 格式 |
|--------|------|
| `.mecon` / `.yaml` / `.yml` | YAML（`StorageScore` 原生） |
| `.xml` / `.musicxml` | MusicXML 4.0 Partwise |

> 暂未支持 Timewise 与压缩（`.mxl`）。

## 2. 模块结构

```
musicxml/
├── MusicXmlConverter.kt    # import / export / isMusicXml
├── MusicXmlParser.kt       # XML → 中间模型（含 DOCTYPE 剥离、backup/forward）
├── MusicXmlWriter.kt       # 中间模型 → XML（含多声部 <backup>）
├── model/                  # MusicXmlScore / Part / Measure / Note / Attributes / Direction
├── import/                 # MusicXmlImporter, PitchConverter, DurationConverter, ImportContext
└── export/                 # MusicXmlExporter, ExportContext
```

三层结构：`StorageScore ⇄ [Importer/Exporter] ⇄ 中间模型 ⇄ [Parser/Writer] ⇄ XML`。
中间模型解耦了 XML 解析与 Mecon 数据模型，便于独立调整两侧。

## 3. 兼容性矩阵

| 功能 | MusicXML | Mecon | Round-trip |
|------|---------|-------|-----------|
| 音高（含变化音） | `<pitch>` | `Pitch` | ✅ |
| 时值（附点、连音比） | `<type>`+`<dot>`+`<time-modification>` | `Duration` | ✅ |
| 休止符 | `<rest/>` | 空 `pitches` | ✅ |
| 休止符显示位置 | `<rest><display-step>/<display-octave>` | `RenderingProps.restStaffPosition` | ✅ |
| 和弦 | `<chord/>` | 多音高 `PitchEvent` | ✅ |
| 延音线 / let-ring | `<tie>` + `<tied>`(含 `let-ring`、placement/orientation、Bézier 属性) | `TieInfo` + `TieGeometry` | ✅ |
| 符杠 | `<beam>` | `BeamingInfo` | ✅ |
| 初始调号 / 拍号 / 谱号 | `<key>` / `<time>` / `<clef>` | `StorageScore.default*` / `StaffTrack.clef` | ✅ |
| **谱号中途变更** | 后续小节 `<clef>` | `StaffTrack.clefChanges` | ✅ |
| **谱表隐藏区间** | `<staff-details print-object="no\|yes">` | `StaffTrack.hiddenRanges` | ✅ |
| **调号 / 拍号中途变更** | 后续小节 `<key>` / `<time>` | `GlobalTrack.events`（`StorageKey/TimeSignatureChange`） | ✅ |
| 多声部（同谱表） | `<voice>` + `<backup>` | 多 `VoiceTrack` | ✅ |
| 多谱表（同声部） | `<staff>` + `<staves>` | `StaffTrack` × N | ✅ |
| **连奏线 Slur** | `<slur>`（编号、placement、Bézier 属性） | `StorageSlurEvent` + `SlurGeometry` | ✅ |
| **连音括弧 Tuplet** | `<tuplet>` + `<time-modification>` | `TupletSpan` | ✅ |
| **装饰音 Grace** | `<grace>`（含 steal-time） | `TimeCode.grace` + `GraceNoteInfo` | ✅ |
| **速度 Tempo** | `<metronome>` + `<sound tempo>` | `GlobalTrack.tempoEvents` | ✅ |
| **力度 Dynamics** | `<direction><dynamics>` | `StorageDynamicMark` | ✅ |
| **渐强渐弱 Hairpin** | `<direction><wedge>` | `StorageHairpin` | ✅ |
| **八度移位 8va/8vb** | `<direction><octave-shift>` | `StorageOctaveShiftStart/End` | ✅ |
| **移调乐器** | `<transpose>` | `StaffTrack.transposition` | ✅ |
| 演奏记号 | `<articulations>` / `<fermata>` | `StoragePitchEvent.articulations` | ✅（常用集合） |
| 装饰 | `<ornaments>` | `RenderingProps.ornaments` | ✅（常用集合） |
| 反复 / 跳房子 | `<barline><repeat>` | `StorageMeasure.repeat*` | ✅（repeat；volta 未映射） |
| **强制分行 / 分页** | `<print new-system/new-page>` | `StorageSystemBreak` / `StoragePageBreak` | ✅ |
| **页面尺寸 / 边距 / 比例** | `<defaults><scaling>` + `<page-layout>` | `PageLayoutConfig` | ✅ |
| **标题 / 副标题 / 作者元数据** | `<work-title>` / `<movement-title>` / `<creator>` / `<credit>` | `ScoreMetadata` | ✅ |
| 跨谱表渲染 | — | `RenderingProps.crossStaffOffset` | ❌ 未映射 |
| 文本表情 | `<words>` | `StorageTextEvent` | ❌ 无存储归属（🚧 见 §6） |
| 歌词 / 和弦符号 / 数字低音 | `<lyric>` / `<harmony>` / `<figured-bass>` | — | ❌ 未实现 |
| 页面题头 / 页脚文字 | `<credit>` | `ScoreMetadata`（标题块相关） | ✅（标题/作者相关） |

支持的演奏记号：`STACCATO / SPICCATO / STACCATISSIMO / TENUTO / ACCENT / MARCATO / FERMATA`（spiccato 导出为 `<staccatissimo>`）。
支持的装饰音：`TRILL / MORDENT / INVERTED_MORDENT / TURN / INVERTED_TURN`。

## 4. 关键转换约定

解析端由 `MusicXmlParser` 编排 document / part / measure / direction / barline，
`MusicXmlNoteParser` 专门解析 note、pitch、rest、notations、articulation 与 ornament 子树。
导出端的自动符杠推导和 `<beam>` 映射集中在 `MusicXmlBeamExport`，主 exporter 只装配
MusicXML 结构。

### 音高
```
MusicXML  step + octave + alter        ↔  diatonicSteps + chromaticOffset
diatonicSteps = (octave - 4) * 7 + "CDEFGAB".indexOf(step)
chromaticOffset = alter
```
`<pitch>` 记录**实际发声音高**，8va/8vb 仅是显示叠加，不调整音高数值。

### 时值
- 导入：优先 `<type>`，否则按 `<duration>` / `<divisions>` 反推；`<time-modification>` → `Duration.tuplet`。
- 导出：固定 `divisions = 1024`。连音（如三连音 1024/3）在除法中有取整误差，音符 onset 可能有微小漂移，但连音比与 `TupletSpan` 结构保留。

### 轨道层级与多声部
```
<part>                       →  顶层 StaffGroup（一个 group = 一个 part）
<staff number>               →  StaffTrack（part 内多谱表）
<voice>                      →  VoiceTrack（part 内全局唯一编号）
<note>                       →  VoiceEvent + PitchEvent
<backup>/<forward>           →  小节内游标回退/前进，用于解析/写出多声部
```
导出时每个声部组写完后用 `<backup>` 回到小节起点再写下一声部；导入时按文档顺序重放游标，
正确还原各声部 onset。

### 符杠（beam）语义

- 导入时：
  - 有 `<beam>` 时，主杠 `number="1"` 映射为 `BeamingInfo.start() / middle() / end()`
  - **可连杠音符**（八分及以下）若**没有** `<beam>`，会写成 `BeamingInfo.NONE`
  - `beaming == null` 仅保留给“未显式指定、允许系统自动分组”的原生 Mecon 数据
- 导出时：
  - `rendering.beaming == null` 的短时值音符，导出器会先按 Computed 层同样的规则做**自动符杠分组**，并写出对应 `<beam>`
  - `BeamingInfo.start() / middle() / end()` 写出 `<beam>`
  - `BeamingInfo.NONE` 不写 `<beam>`，保留独立符尾

因此，MusicXML 中“没有 `<beam>`”对短时值音符并不是缺失信息，而是“显式不连杠”。
同时要注意：Mecon 原生数据里 `rendering.beaming == null` 表示“允许系统自动”；一旦导出为 MusicXML，再重新导入时，这些自动分组会变成显式的 `BeamingInfo.start()/middle()/end()`。

### Tie / slur 几何

MusicXML 4.0 的 `<tied>` / `<slur>` 可携带 `placement` / `orientation`、`relative-x/y`、
`default-x/y` 与 `bezier-x/y`。Mecon 的互操作规则：

- `placement` / `orientation` 映射为 `above` + `directionLocked`。
- `bezier-y`（tenths）映射为本地弧高；导出时按 `10 tenths = 1 staff space` 写回。
- `relative-x/y` 映射为音头锚点相对端点偏移；Y 轴在 MusicXML 中向上为正，进入屏幕坐标时取反。
- `default-x/y` 的坐标原点依赖小节与谱表最终版式；导入阶段不把它误当作音头相对偏移。
- 跨系统的 `continue` 复杂曲线暂按本地 stub 自动排版；方向仍保留。

### 谱号 / 调号 / 拍号变更
- **谱号**：初始 = `StaffTrack.clef`（首小节 `<clef>`）；中途变更 = `StaffTrack.clefChanges`（对应小节的 `<clef>`）。
- **调号 / 拍号**：初始 = `StorageScore.defaultKey/TimeSignature`；中途变更 = `GlobalTrack.events`
  （`StorageKeySignatureChange` / `StorageTimeSignatureChange`，记在变更小节起点）。
  > `StorageMeasure.keySignature/timeSignature` 为遗留字段，导入不再写入；运行时两者皆兼容读取。

### 谱表隐藏（hidden ranges）
- **机制**：每谱表在进入隐藏区的小节写 `<attributes><staff-details print-object="no"/>`，离开时写 `print-object="yes"`（多谱表 part 带 `number="N"`）。这是 MuseScore/Finale 通用做法。
- **导入**：`MusicXmlImporter` 扫描每谱表 `print-object` 翻转点成对折叠为 `MeasureRange`；末尾未关闭则延伸到该 part 末小节。
- **导出**：`MusicXmlExporter` 由 `StorageStaffTrack.hiddenRanges` 逐小节生成翻转记号。

### 8va / 8vb（octave-shift）
沿用 Finale/MuseScore 约定：

| Mecon | MusicXML `<octave-shift type>` |
|-------|-------------------------------|
| `OTTAVA`（8va，高八度） | `down` |
| `OTTAVA_BASSA`（8vb，低八度） | `up` |

起始记 `StorageOctaveShiftStart`，结束记 `StorageOctaveShiftEnd`，二者通过 `endEventId` 配对，
均存于 `StaffTrack.attachments`。

### 力度记号位置
导出时把 direction（力度 / hairpin / 速度）放在所属小节开头（beat 0）。小节内非整拍位置的
力度在往返后会归位到 beat 0——这是当前的已知精度取舍。

### 分行 / 分页与页面布局
- `<print new-system="yes">` → `StorageSystemBreak(onset = 该小节起点)`；`new-page="yes"` →
  `StoragePageBreak`，并在 runtime 中隐含同小节的 system break。导出 page break 时同时写
  `new-system="yes" new-page="yes"`，便于外部软件按新页起新行解释。
- 第一小节上的 `<print>` 只作为初始页面标记处理，不生成“第 1 小节前强制断点”事件。
- 导入出现 `<print>` 断点或 `<defaults><page-layout>` 时，会把 `StorageScore.pageLayout.paginated`
  置为 `true`，让打开后直接按分页版式渲染。
- `<defaults><scaling>` 使用 MusicXML 的 tenths↔mm 换算：Mecon 固定约定 `10 tenths = 1 staff space`，
  因此 `staffSpaceMm = millimeters / tenths * 10`。`<page-layout>` 的纸张尺寸和
  `<page-margins type="both">`（无 both 时取第一组 margins）映射到 `PageLayoutConfig`。

### 标题 / 作者元数据
- 导出时：
  - `ScoreMetadata.title` → `<work><work-title>`，并额外写 `credit-type=title` 的 `<credit>`
  - `ScoreMetadata.subtitle` → `<movement-title>`，并额外写 `credit-type=subtitle` 的 `<credit>`
  - `ScoreMetadata.composer / arranger / lyricist` → `<identification><creator type="...">`
  - `ScoreMetadata.copyright` → `<identification><rights>`，同时写 `credit-type=rights` 的 `<credit>`
- 导入时优先读取标准元数据标签（`work-title / movement-title / creator / rights`），缺失时回退到
  第 1 页 `<credit>`：
  - 优先使用显式 `credit-type`
  - 若无 `credit-type`，则按常见排版启发式推断：最大号居中的 credit 视为标题，下一条居中 credit
    视为副标题，右对齐 credit 视为作者
- `credit` 的定位属性（`default-x/default-y/justify/...`）目前仅用于元数据识别与基础导出布局，
  不尝试完整保真不同软件的封面排版。

### Mecon → MusicXML 的损失项
- 分离的 `PitchTrack` / `VoiceTrack` ID 在 MusicXML 中合并；重新导入时 ID 会重建。导入器按
  `(staff, voice)` 建立一对独立轨道，避免钢琴双谱表的同时事件在播放时间轴上互相截断。
- 延音线只写出起始 `<tie type="start"/>` / `<tied type="start"/>`，不写配对的 `stop`
  （Mecon 用前向启发式解析延音目标，往返自洽；外部阅读器可能显示悬空延音线）。
- Let-ring（`TieInfo.isLetRing`）用 `<tied type="let-ring"/>` 表示（MusicXML 4.0）。
- `PageLayoutConfig.presetName` 不写入 MusicXML；重新导入时页面尺寸保留，但 preset 归为 custom。

## 5. 错误处理

`import` / `export` 返回 `Result<T>`。
- `MusicXmlParser.parse` 会先剥离 `<!DOCTYPE …>`，避免 StAX 解析器尝试联网拉取外部 DTD。
- 未识别的元素当前忽略并继续。
- 常见失败：非 Partwise 结构（Timewise / 压缩 mxl）。

## 6. 未映射说明

- **文本表情（`<words>`）**：`StorageTextEvent` 已定义但目前未挂入 `StorageScore` 任何轨道、
  渲染层也未消费，故暂不往返。待其有正式存储归属后再补。🚧
- **跨谱表 `crossStaffOffset`**、**volta 跳房子**、**和弦符号/数字低音/歌词**：
  见矩阵，均未映射。

## 7. 测试入口

`core/src/commonTest/.../musicxml/MusicXmlRoundTripTest.kt`：对每项已支持功能做
导出 → 重新导入 → 断言关键字段一致的 round-trip 验证。


## 乐器与 part

MusicXML 的每个 score-part 导入为一个 StorageInstrument：part 名称/缩写、全部 staffIds 与 midi-program（MusicXML 1-based ↔ 内部 0-based）一并保留。导出优先按 instruments 切分 part，而不是把显示用 staffGroups 误当作乐器边界。
