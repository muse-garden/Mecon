# 新里曼 / Voice-leading 变换

本能力为自由练习提供不依赖和声功能的和弦邻接图，可与勋伯格章节同时使用：惯用进行面板以独立 tab
展示勋伯格目录与 voice-leading 候选，后者插入为独立 `WorkspaceChordChoice`，再由共享写作会话完成
实际声部配置。

## 1. 变换定义

核心实现位于 `theory/.../voiceleading/VoiceLeadingTheory.kt`。

- 节点是 pitch-class 集合；变换计算不依赖音级、调式或等音拼写；
- 一条边只移动一个原始和弦音，上/下 `1` 或 `2` 半音；移动后必须仍能被同一和弦族识别；
- 路径保留原音身份，后续步骤不得再次移动已经移动过的原音。因此 `A→B→C` 不是两个声部的
  两步变换；
- 路径按顺序保存 `sourceToneIndex / fromPitchClass / toPitchClass / semitones`，供后续复杂声部进行、
  和弦外音与实际配器使用；
- 同一目标只展示最短步数，但保留全部最短有序路径。

标准注册族：

| family | 类型 | 最多步数 |
|---|---|---:|
| `tertian.triad` | 大、小、增、减三和弦 | 2 |
| `tertian.seventh` | 大七、小七、属七、减七、半减七、小大七、增七、属七降/升五 | 3 |

`VoiceLeadingChordFamily` 直接接收开放式 `ChordDefinition`；未来高叠和弦或其他集合只需注册定义、族 ID
和最大步数，不改遍历器。增三和弦和减七和弦保留全部等价根音读法，但 pitch-class 节点只生成一次，
避免按根音解释重复列举同一音响。

## 2. 七和弦过滤

三步路径的三个步骤必定属于三个不同原音。若三音全部向上或全部向下，路径标记
`threeTonesSameDirection=true`。共享 view 同时给出候选在过滤开启后是否仍有其他合法最短路径；
Desktop/Web 的瞬时开关只重放这些 typed 标记，不重新计算变换。

## 3. 平行完全音程风险

变换图只能看到和弦成员身份，不能预知最终四部排列，因此这里输出风险而非硬禁则：

- 两个同向移动的原音在移动前后均保持纯五度，标记 `PARALLEL_FIFTH`；
- 存在两个以上同向移动音时，若实际写作重复了其中的移动成员，可能产生平行八度，标记
  `PARALLEL_OCTAVE_IF_MOVED_TONE_IS_DOUBLED`。

例如 `1–3–5 → ♯1–3–♯5` 的根音与五音同向升半音并保持纯五度，会同时显示平五风险及“移动音被
重复时可能平八”的提示。最终是否违规仍由实际四部 voicing 的通用规则判定。

## 4. 根音方向与无功能标记

变换本身不使用调内音级，但展示层会从所有对称根音读法中，为当前前后连接选择最稳的一对根音。
半音根音距离按勋伯格方向语义投影：

| 分类 | 半音根音方向 | 提示 |
|---|---|---|
| 上升进行 | 上行纯四度；下行大/小三度 | 较稳，优先考虑 |
| 下降进行 | 上行大/小三度；下行纯四度 | 较弱，宜由后续补偿 |
| 超越进行 | 上/下大、小二度 | 宜节省使用 |
| 同根音 | 0 | 色彩变化，不是根音推进 |
| 未分类 | 三全音 | 不强套调内三类 |

这个选择只决定候选说明，不改变音高集合；因此对称和弦作为前和弦和后和弦时可以采用不同根音读法。
UI 使用稳定 `colorToken` 区分上升、下降、超越及中性关系。

音列展示另以当前和弦的 `preferredRootPitchClass` 为首，按和弦定义排成根–3–5–7；目标音列沿用这些
原始和弦音的列位，只在被变换的列显示目标音，不按目标音级重新排序。例如
`7-♯2-4-6 → 1-♯2-♯4-6`。该根音提示随 voice-leading 插入持久化，但不添加功能或写作约束。

候选可能不属于当前功能目录。此时 `PracticeVoiceLeadingCandidateView` 始终提供可直接提交的
`WorkspaceChordChoice`，并只显示绝对字母符号（`C`、`Dm`）或相对音级符号（`1`、`2m`），不伪造
`I/ii` 功能。提交后时间轴也从存储的音集和首选根音生成同样的双模式回退标签，不能把已占用槽位
投影成空的“选择和弦”。

## 5. 自由练习接入与验证

`FreePracticeViewProjector.plan` 根据当前槽和活动调性生成 `PracticeVoiceLeadingView`；Compose/React
只显示分组、路径、警告、颜色及 ready-to-dispatch choice。选择候选走
`FreePracticeIntent.InsertVoiceLeadingChord → FreePracticeSession`；共享 session 重新验证目标与有序最短
路径。源和弦后已有和弦框时直接替换该框的和弦并保留其 ID、位置和时值；源和弦为末框时才追加一个
同长度和弦框。一次动作只形成一个历史项，并与勋伯格目录共享 revision、自动写作与失败原子性。该动作
不创建 `WorkspaceIdiomInstance`，所以时间轴没有惯用进行线。

门禁：

- `VoiceLeadingTheoryTest`：族类型、一步/两步/三步、原音不可复用、对称根音、平五风险和根音分类；
- `FreePracticeViewProjectorTest`：三/七和弦 typed view、过滤标记与可提交 payload；
- `practice-trace.json`：JVM/JS 对同一候选、路径风险、独立插入结果重放等价；
- Web 架构/组件测试与 Desktop 编译保证两端只消费共享投影。
