# 乐谱单文件格式（YAML / JSON）

> 序列化器：`StorageScore` (`@Serializable`) + `kaml`（YAML）/ kotlinx JSON。**单乐谱文本**扩展名
> `.mscore.yaml` / `.yaml` / `.yml`（原 `.mecon`）。
>
> 打包多乐谱、模块与冻结几何的 **`.mecon` 容器**（zip）见
> [mecon-container.md](mecon-container.md)——其 `scores/<id>.json` 条目正是本文所述结构的 JSON 形式。

## 1. 顶层骨架

```yaml
metadata:
  title: "曲名"
  composer: "作者"
defaultTimeSignature: { numerator: 4, denominator: 4 }
pitchTracks:   { ... }   # Map<TrackId, StoragePitchTrack>
voiceTracks:   { ... }
staffTracks:   { ... }
partTracks:    { ... }
pluginTracks:  { ... }
globalTrack:   { ... }
partOrder:     [ ... ]
reductions:
  - id: "reduction-1"
    title: "缩谱"
    layers:
      - id: "reduction-1-notation"
        kind: NOTATION
        visible: true
        score: { ... }
    materialTray:
      - id: "fragment-1"
        name: "主题 A"
        kind: MELODIC
        score: { ... }
```

字段定义：[storage.md](storage.md) 第 2 节，源代码见 `api/.../storage/StorageScore.kt`。
旧格式中 reduction 下的单个 `score` 字段会在读取时自动迁入 `NOTATION` 层。

## 2. TimeCode 写法

`TimeCode` 是 `List<Fraction>`，字典序比较。两种 YAML 形式：

```yaml
# 紧凑形式 (TimeCodeSerializer)
onset: "0:1/4"          # measure=0, beat=1/4

# 列表形式
onset:
  - { numerator: 0, denominator: 1 }
  - { numerator: 1, denominator: 4 }
```

`components[0]` = 小节号（从 0 开始）；`components[1]` = 小节内位置（四分音符 = 1）。

## 3. Pitch 写法

```yaml
pitches:
  - { diatonicSteps: 0, chromaticOffset: 0 }   # C4
  - { diatonicSteps: 2, chromaticOffset: 0 }   # E4
  - { diatonicSteps: 3, chromaticOffset: 1 }   # F#4
```

- `diatonicSteps`：C4 = 0，跨八度递增（C5 = 7）
- `chromaticOffset`：-2..+2，对自然音的半音偏移

详见 [primitives.md](primitives.md) 第 3 节。

## 4. 事件示例

### 单音 + 和弦 + 休止

```yaml
pitchTracks:
  pt_main:
    id: { value: "pt_main" }
    events:
      - id: { value: "p1" }
        onset: "0:0"
        pitches: [ { diatonicSteps: 0, chromaticOffset: 0 } ]
      - id: { value: "p2" }
        onset: "0:1/4"
        pitches:                                # 和弦
          - { diatonicSteps: 0, chromaticOffset: 0 }
          - { diatonicSteps: 2, chromaticOffset: 0 }
          - { diatonicSteps: 4, chromaticOffset: 0 }
      - id: { value: "p3" }
        onset: "0:2/4"
        pitches: []                              # 休止
```

### VoiceEvent 与延音线

```yaml
voiceTracks:
  vt_main:
    id: { value: "vt_main" }
    voiceNumber: 1
    pitchTrackId: { value: "pt_main" }
    events:
      - id: { value: "v1" }
        onset: "0:0"
        pitchEventId: { value: "p1" }
        duration: { base: QUARTER, dots: 0 }
        ties:
          - pitchIndex: 0
            targetEventId: { value: "v2" }      # null = let-ring
```

延音线语义见 [storage.md](storage.md) 第 4 节。

## 4.5 谱表头字段

```yaml
# StorageStaffTrack — 单行谱表独立标签（如合唱声部 S./A./T./B.）
staffTracks:
  st-soprano:
    id: "st-soprano"
    clef: TREBLE
    voiceTrackIds: ["vt-soprano"]
    staffLabel: "S."
    staffLabelAbbreviation: "S."

# StoragePartTrack — 声部括号与小节线连接
partTracks:
  part-choir:
    id: "part-choir"
    instrumentName: "Choir"
    staffTrackIds: ["st-soprano", "st-alto"]
    innerBarlineConnect: true   # 声部内各行共用小节线
    partBracket: NONE           # 禁止自动 BRACE；改用 staffGroups 里的 SQUARE

# staffGroups — 顶层列表，每项描述一个括号层级
staffGroups:
  - id: "sg-choir"
    bracket: SQUARE
    label: "Choir"
    abbreviation: "Chr."
    barlineConnect: true
    members:
      - type: part              # StaffGroupMember.Part（kaml 多态判别符）
        partId: "part-choir"
  - id: "sg-strings"
    bracket: SQUARE
    label: "Strings"
    barlineConnect: true
    members:
      - type: part
        partId: "part-violin"
      - type: group             # StaffGroupMember.Group — 嵌套子组
        group:
          id: "sg-vln-sub"
          bracket: SUB_BRACKET
          members:
            - type: part
              partId: "part-violin-i"
            - type: part
              partId: "part-violin-ii"
```

## 5. 完整示例

`test-scores/` 下提供了 10 个分类样本，覆盖时值、符杠、和弦、休止、复调、大谱表、延音、附点连音、复杂节奏与大跨度琶音：

| 文件 | 演示要点 |
|------|---------|
| `01_durations.mscore.yaml` | 各种基本时值 |
| `02_beaming.mscore.yaml` | 符杠分组 |
| `03_chords.mscore.yaml` | 和弦写法 |
| `04_rests.mscore.yaml` | 休止符（空 `pitches`） |
| `05_polyphony.mscore.yaml` | 同谱表多声部 |
| `06_grand_staff.mscore.yaml` | 钢琴大谱表 |
| `07_ties.mscore.yaml` | 显式 / 隐式 / 部分和弦延音 |
| `11_chord_analysis.mscore.yaml` | 含 `pluginTracks` 的和弦标记（I–vi–IV–V7 / ii7–V7–Imaj7） |
| `17_staff_groups.mscore.yaml` | 合唱 SQUARE 括号 + 钢琴花括号 + 分段小节线 |

使用 `ScoreFileService.loadAuto(file)` 加载。

## 6. 验证清单

手写或导入乐谱时检查：

- [ ] 所有 `id` 唯一
- [ ] `voiceTrack.pitchTrackId` 指向存在的 `pitchTrack`
- [ ] `voiceEvent.pitchEventId` 指向存在的 `pitchEvent`
- [ ] 和弦 `pitches` 按从低到高排列
- [ ] `tieInfo.pitchIndex < pitches.size`
- [ ] `tieInfo.targetEventId` 指向存在的事件，或为 `null`（let-ring）
- [ ] 休止：`pitches: []`

## 7. 与 MusicXML 互转

通过 `MusicXmlConverter.import / export` 与扩展名 `.xml` / `.musicxml`，详见 [musicxml.md](musicxml.md)。


## 乐器映射

顶层 instruments 将一行或多行谱表映射到同一播放乐器；未写时按旧格式兼容为钢琴。playback 保存 MIDI bank/program，并预留 soundFontId、pluginId、pluginState。staffGroups 只控制显示顺序、括号和连小节线，不代表播放乐器。
