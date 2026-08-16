# 自由练习数据模型

> ✅ 当前 schema v8。逐和弦临时调性、可听音响 + 可选锁定解释、可重叠惯用进行、双谱表、
> 复音上限、自动写作/回放设置、手工/自动记谱来源与分析声部分离均已落地；见
> [自由练习自动写作改造](../exploration/free-practice-auto-writing.md)。

## 1. 三个概念必须分离

自由写作不再把一条存储声部等同于一条分析单音声部：

| 概念 | 职责 | 持久化位置 |
|------|------|-----------|
| 复音上限 | 任一时刻允许同时发声的音符总数 | `FreePracticeSettings.polyphonyLimit` |
| 记谱通道 | 双谱表内承载符干、休止与手工编辑的可写通道 | `StorageVoiceTrack` |
| 分析声部 | 分析前从所有发声音符临时拆出的单音轨迹 | 分析输入帧，不回写源谱 |

同一记谱通道的一个 `PitchEvent` 可以包含多个音高。写入是否合法只由全谱半开区间
`[onset, end)` 上的同时发声音符数决定，而不是由记谱通道是否已有事件决定。

## 2. 模块设置

当前 schema v8 的模块设置使用：

```kotlin
FreePracticeSettings(
    polyphonyLimit: Int,
    staffVoices: GrandStaffVoiceLayout,
    initialKey: KeySpec,
    selectedPatternIds: List<String>,
    defaultChordDuration: Fraction,
)

GrandStaffVoiceLayout(
    upperVoiceCount: Int,
    lowerVoiceCount: Int,
)
```

约束：

- `polyphonyLimit` 为 3–6；
- 上、下谱表各至少一个记谱通道；
- `staffVoices.capacity == polyphonyLimit`；
- `workspace.voices.size == polyphonyLimit`，其中 workspace voice 仅作为稳定的默认记谱通道
  描述与分析配置来源，不保证源事件单音。
- `defaultChordDuration` 是顶栏“默认和弦拍数”的持久化真相，必须为正值；旧文件缺省为
  四分音符。练习尚未出现手工和弦或音符编辑时，修改它同时调整首个默认主和弦槽。

`selectedPatternIds` 只为旧文件兼容保留，新 UI 不再写入；惯用进行实体存于
`workspace.idiomInstances`，当前选中实例属于 session 瞬态 selection。

### 2.1 schema v6 引入的写作设置 ✅

自动写作设置属于模块文档偏好，不属于和声槽或 `RuntimeScore`：

```kotlin
@Serializable
data class FreePracticeWritingSettings(
    val autoWritingEnabled: Boolean,
    val backtrackChordCount: Int = 0,
    val replayChordCount: Int = 1,
    val playbackTempoBpm: Int = 120,
)

@Serializable
data class FreePracticeSettings(
    // 当前其他字段……
    val writing: FreePracticeWritingSettings,
)
```

- 回溯数为 `0..16`；`0` 表示只写当前和弦；
- 回放数为 `0..16`；`0` 表示写作后不自动回放；
- 速度为 `30..240` BPM，只覆盖自由练习预听，不复制成谱面 tempo 事件；
- 新建练习默认开启自动写作；v1–v5 迁移默认关闭，由 factory/migrator 显式区分；
- v1–v5 的 BPM 从关联乐谱起点有效速度取整并裁剪，读取失败回退 120；迁移后不再随谱面同步；
- 设置变更随模块保存，但不创建撤销项；自动写出的 `RuntimeScore` 材料必须创建撤销项。

### 2.2 拍号、小节与时间线边界 ✅

- 实际小节列表与各小节有效拍号只存于关联 `StorageScore`，workspace 不复制拍号或小节数量；
- `PracticeTimelineView.end` 由共享 session 按谱面全部小节的实际时长投影，因而必定落在末端
  小节线上。共享投影同时用 `PracticeTimelineView.emptySlots` 发布一个从和声内容末端连续延伸至末端
  小节线的纯视觉填充块；它不按默认和弦时值或小节边界分段，不显示文字、不可选择，并使用区别于
  真实空和弦槽的背景色。
  填充块不是绿色的末尾追加按钮，也不进入 hit-test、selection 或 accessibility action；
  它只表示尚未占用的时间。绿色“＋”始终作为独立单元画在末端小节线之后，不与空位共享宽度，
  也不会因新增一个空和弦槽而消失；其业务插入点仍取当前 workspace 材料末端，因此点击后优先通过
  `InsertChordRange` 按默认时值占用并缩短小节线前的空位，填满后才向后延伸。桌面与 Web 必须直接消费 session 发布的 typed view，不得只从
  workspace 重投影，否则会丢失 `scoreEnd` 和空位。空位的显示边缘必须与实际雕刻小节线对齐；
- 时间线拖动提交若把一个或多个尾部小节完整空出，共享 session 会在同一事务内删除其中没有实际
  音符到达的尾部小节；实际音符的延音末端同样会保护对应小节，undo/redo 同时恢复谱面与 workspace；
- renderer 的对齐时间轴同时发布实际雕刻小节线的边界坐标。和声时间轴以该坐标
  作为小节终点，不使用带小节线右侧排版留白的 unified-slot X；
- 自由练习仍为默认状态（只有初始主和弦且没有手工音符）时，“设置拍号”替换谱面总体拍号；
  出现编辑材料后，顶栏“调整拍号”只负责选择拍号并启用平台的通用拍号笔。用户在谱面悬停时
  预览目标小节，点击后必须用 `FreePracticeIntent.Score(ScoreEditIntent.SetTimeSignature)` 进入
  `ScoreEditingSession`，由 `TimeSignatureEditEngine` 完成局部拍号写入、后续重分小节、结构选择、
  render hint 与单历史项；外层 `SetPracticeTimeSignature` 在非默认状态下必须拒绝，平台不得据当前
  选区猜测目标小节后直接提交。Web 拍号候选和幽灵预览均使用 Bravura 的 SMuFL 拍号数字/C 拍/
  切分拍字形，不使用普通文本数字替代；
- 插入小节是外层 `FreePracticeIntent.InsertPracticeMeasures` 的原子操作：通用
  `MeasureEditEngine` 移动谱面材料，workspace 同时平移插入点后的和弦/调性范围，并按
  `defaultChordDuration` 在每个新小节内独立填充空和弦槽。单个槽不得跨越新小节线，装不下的
  尾拍保持为空；
- 和弦槽延伸超过现有谱尾时只追加所需小节，不再因后续槽缩短而自动删除用户显式保留的空小节。

以下状态不得持久化：求解候选、最后写作范围、diversity seed、busy/诊断、乐谱选区、播放位置
和撤销栈。最后写作范围使用稳定 `WorkspaceSlotId` 的瞬态列表，不能保存易失的 EventId 或下标。

当前 v8 和声槽位以 `WorkspaceHarmonySlot.chordChoice` 保存 `WorkspaceChordChoice`。
`chordInterpretationRef` 只用于解码 v4，`chordIdentity` 只接收 v1–v3 旧文件中尚未解析的显示符号；
新建、选择、惯用进行和自动写作均不得写入这两个旧字段。

## 3. schema v5 引入的和声选择 ✅

```kotlin
@Serializable
data class WorkspaceChordChoice(
    val pitchClasses: List<Int>,
    val origin: ChordSelectionOriginRef? = null,
    val pinnedInterpretationRef: ChordInterpretationRef? = null,
    val bassPitchClass: Int? = null,
)
```

- `pitchClasses` 以排序、去重的 0..11 源值固定实际音响，运行时据此计算 `AudibleSonorityKey`；
- `origin` 记录用户从哪个门类和卡片进入，只影响显示恢复与解释排序；
- `pinnedInterpretationRef` 非空表示锁定拼写、功能和规则，空值表示自由解释；
- `bassPitchClass` 非空时固定该和弦音为低音，空值表示任一和弦音均可作低音；非空值必须包含在
  `pitchClasses` 中；
- 锁定引用必须与存储的 pitch classes 同音，否则 reducer 拒绝命令；
- 未锁定时各候选解释作为互斥搜索分支，禁止合并规则或默认取第一项。

点击目录中的和弦立即写入 `pinnedInterpretationRef = null` 的自由选择，并形成一次撤销事务；
用户随后确认线路时再以锁定引用替换同一槽位，形成第二次撤销事务。只浏览线路不修改工作区。
惯用进行、教材练习和已明确选择线路的操作仍可直接写入锁定解释。
所有插入、替换、范围插入和进行命令最终统一接收 `WorkspaceChordChoice`。

新建自由练习的首个主和弦以结构根音作为低音；用户在首槽重新选择和弦时也先恢复原位。
其他手工选择的和弦默认 `bassPitchClass = null`，用户可再从和弦音中指定低音。和弦目录的
相对/绝对音高读法同时控制低音选项标签；改变解释线路不得丢失仍属于该音响的低音选择。

## 4. 双谱表存储

自由练习谱固定使用两个稳定谱表 ID：

- `free-practice-upper-staff`
- `free-practice-lower-staff`

谱表用花括号分组并连接小节线。记谱通道和 pitch track 继续保留原稳定 ID；只更新
`voiceNumber` 与谱表成员关系。这样手工调声部、和弦音头拆分以及后续分析都可复用通用
`NoteEditEngine`，无需引入自由练习专属音符格式。

## 5. 迁移

读取旧模块时：

1. `voiceCount` 映射到 `polyphonyLimit`；
2. 默认按 `ceil(N / 2)` / `floor(N / 2)` 分到上下谱表；
3. 旧的一声部一谱表合并为两个谱表；
4. 保留 voice track、pitch track、事件、音符、延音线与所有事件 ID；
5. 在槽位调性中用知识/选择目录解析旧 `chordIdentity`：仅有一个候选时升级为
   `ChordInterpretationRef`；无候选或多候选时保留旧符号并写入
   `FreePracticeMigrationDiagnostic`，不得静默选第一条；
6. 保存时统一写出当前 schema v8。

实施 v5 时，v4 的精确引用迁入 `pinnedInterpretationRef`，并在槽位调性中解析
pitch classes。唯一门类来源可补入 `origin`；多门类时留空即可，因为 origin 不参与音乐规则。
若 provider 缺失导致 pitch classes 无法解析，保留可见迁移诊断，不得改选其他解释。

迁移属于模块装配边界：payload 解码只负责设置字段升级，谱表合并需要同时拿到模块 payload
和 `StorageScore`，不能隐藏在 Renderer 或 Runtime 转换中。

## 6. 写作与分析边界

- 钢琴卷轴写入先选择默认记谱通道，再以 `CHORDAL` 合并或插入事件；
- 五线谱手工编辑可按音头在上下谱表及记谱通道间移动；
- 每次提交编辑前对候选 Runtime 帧执行复音上限校验；
- 分析启动时把所有和弦音头展开为独立 note span，再运行单音声部分离；
- 分析结果只保存音头到分析轨迹的映射，不改变用户记谱组织。
- 自动写作只从 `WorkspaceChordChoice + RuntimeScore` 派生局部请求，不在 workspace 保存第二份
  voicing；范围物化直接原子替换 Runtime 音符，范围外材料保持不变；
- 自动写作将非空 `bassPitchClass` 投影为槽位目标过滤；空值保留该音响的所有可用转位；
- `HarmonyWorkspaceState.voiceAssignmentSources` 按稳定 `EventId` 保存 `MANUAL` / `AUTOMATIC` 来源，
  仅服务自动记谱重分配保护，不把记谱通道冒充分析声部。Web/桌面手工插入在同一 session 事务中写入
  `MANUAL`；删除、移动、undo/redo 与 score 一起原子维护映射。钢琴卷轴自动入口可写 `AUTOMATIC`，
  且不得覆盖已有 `MANUAL` 来源。

## 7. schema v7 惯用进行成员关系

`WorkspaceIdiomInstance.slotIds` 是惯用进行与和弦槽成员关系的唯一持久化真相；槽不再保存单值
`idiomInstanceId`。同一个稳定 `WorkspaceSlotId` 可以同时出现在多个实例中，例如
`V/V–V` 与 `V–I` 共享中间的 V。

- 被至少一个实例引用的槽仍禁止逐槽破坏性编辑；锁定状态从 instances 派生；
- 插入进行时，调性、精确解释与时间边界兼容的既有惯用进行槽可直接共享；只有新进行锁定
  转位时才要求低音兼容，开放转位步骤不得用目录枚举出的常用低音阻止共享；
- 惯用进行只锁定和弦身份与时间结构，不默认锁定转位。`inversionLockedSlotIds` 由教材章节规则
  投影：正格终止末尾的原位 `I/i` 与结构性终止 `I64` 保持固定。其余新建槽以目录常用低音
  作为初值，复用槽仅在原低音为“任意”时采用该初值；两者之后都可单独选择任意合法低音。旧 v7
  实例没有该字段时，保留原文件中已经固定的低音语义；
- 删除实例只移除 `WorkspaceIdiomInstance`，其全部和弦槽保持不变；仍被其他实例引用的共享槽继续锁定，
  其余槽解除惯用进行锁定并成为普通时间轴和弦；
- 替换实例若要改写其他实例仍引用的共享和弦，命令原子失败；
- 章节惯用进行若只展示原教学 program 的一个子区间，`parameters` 必须携带**一条**
  `free-practice.teaching-source`：源调、完整 source progression、可见区间起点 `start`，
  以及章节编译时使用的终止式选项。规则投影按该 provenance 重编译，不再从当前音响、调性、
  终止选项或枚举结果反向猜测原 program。**终止式选项必须随之持久化**——
  `includeCadentialSixFour` 为假的 program 既不含 I64 槽也不带六四规则，含 I64 的变体一旦
  丢掉该字段就投影不出自己要教的规则；
- `start` 只在「章节确实按该 progression 原样编译」时（`program.length` 等于 progression 槽数）
  用于定位；把 program 拓宽到自身教学长度的章节（终止式练习）改由 `findTargetSpan` 搜索，
  避免用源进行的下标去索引另一长度的 program。整条记录由
  `SchoenbergTeachingSourceCodec` 编解码，调用方不得另写字段拼接；
- v6 的槽级 `idiomInstanceId` 解码时作为未知旧字段忽略，因为同一文件中的 instance 已保存完整
  `slotIds`；再次保存统一写出当前 schema。

相关交互与投影设计见
[`../exploration/free-practice-workbench-interaction-v2.md`](../exploration/free-practice-workbench-interaction-v2.md)。

## 8. schema v8 逐和弦临时调性 ✅

实体调性线与逐和弦调性是两层独立真相：

- `WorkspaceTonalLayout` 继续保存用户手工插入的实线，可拖动、改调与删除；插入离调进行不得
  缩短、删除或改写既有实体线；
- `WorkspaceHarmonySlot.tonality` 保存和弦自身的调性解释。非空时，它在和弦目录、检查、自动
  写作与教学规则中优先于实体线；为空时才回退到所选实体线；
- 虚线调性区间只由相邻和弦标记派生，不重复持久化，也不提供端点拖动或区间删除；
- 普通和弦沿实体线继续输入时保持 `tonality = null`，不得把实体线调性复制成逐和弦标记；人工
  插入新实体线并终止旧线时，枢纽结束后的同调冗余单标记会回退实体线，避免再投影一条旧调虚线。
  在重叠实体线间切换和弦目录时，同样移除与任一实体线重复的单标记，由所选实体线决定目录；真正
  离调或双重解释的标记仍独立保留；
- 和弦标记不记录由哪个惯用进行写入。删除惯用进行只解除实例成员关系与编辑锁定，不回收和弦、
  音符或调性标记；解除锁定后由用户逐和弦清理。

```kotlin
@Serializable
data class WorkspaceChordTonalReading(
    val fifths: Int,
    val mode: WorkspaceKeyMode,
    val interpretationRef: ChordInterpretationRef? = null,
)

@Serializable
data class WorkspaceChordTonality(
    val primary: WorkspaceChordTonalReading,
    val alternates: List<WorkspaceChordTonalReading> = emptyList(),
)
```

Schema v9 adds persistent harmonic-role marks and dynamic writing-lock rules. Their stable identity,
onset semantics, and staff/voice lock behavior are specified in
[`free-practice-note-constraints.md`](free-practice-note-constraints.md).

`primary` 是后续普通和弦/进行默认继承的调性；`alternates` 是当前和弦同时成立的其他明确解释。
同一调性在一和弦内只能出现一次。删除 primary 时，首个 alternate 提升为 primary；删除最后一个
标记后 `tonality = null`，重新回退实体线。

逐和弦标记不在“当前调性”面板中编辑；实体线仍由该面板独立管理。和声选择中的“离调”区集中
处理两类操作：当前和弦若紧跟带临时调性的前一和弦，可直接选择延续哪一个调或回到实体线原调；
也可为当前和弦创建双重调性解释。双重调性候选从当前可听音响在所有支持调性下的精确功能解释
生成，同时展示功能、调内构成音，并按相对实体线调性的升降号变化排序。该选择不创建转调枢纽，
也不自动把和弦标成多调公共和弦。延续/终止按钮始终列出可选调性；当前和弦在某调下无解释时
只显示调名，选中后在同一事务中清空和弦并保留新调性上下文，等待用户重新选和弦。

离调惯用进行必须携带源调、目标调和逐步精确解释。插入或替换时一次事务重写完整范围：

惯用进行目录的输入调是共享 session 的瞬时选择，不写入 workspace，也不复用和弦的
`tonalLayoutId` 筛选状态。真正插入后，所选实体线 ID 写入 `WorkspaceIdiomInstance.tonalLayoutId`；
若锚点只有一条活动线则自动采用它，区间终点按半开区间处理，不把刚终止的前调列为候选。

1. 首和弦：源调为 alternate、目标调为 primary，两个解释都必须明确；
2. 后续和弦：只保留目标调 primary；
3. 非离调进行：全部步骤只使用用户选择的输入调性；
4. 后续输入默认继承前一和弦 primary，也可由用户显式选择实体线中的原调或任意其他调；
5. 连续离调 `C→D→E` 形成 `C/D, D/E, E...`，不会把远端 C 累积到 `D/E`；目标重新选择 C
   时同样按 `E/C, C...` 自然返回原调。

惯用进行实例保存自己期望的逐步调性，以便重叠实例在替换前验证共享和弦仍满足其他实例；这不是
和弦标记 provenance，不能用于删除实例时反向清理标记。若派生虚线与同调实体线完全重合，显示层
省略重复虚线；和弦标记仍保留，实体线移开后虚线自动显现。
