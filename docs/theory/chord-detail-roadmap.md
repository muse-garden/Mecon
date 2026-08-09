# 和弦音响与多重解释 Roadmap

> 状态：R1–R4C ✅ 已实施；R5–R7 🚧 待实施。
> 本文只安排依赖顺序和验收门槛，不承诺日历时间。
>
> 设计：[chord-detail-and-vagrant-chords.md](chord-detail-and-vagrant-chords.md)

## 1. 交付目标

完成后应满足：

1. 任一宿主可用稳定解释引用取得同一份章节详情。
2. 自由练习不再拥有和弦百科或目的地表。
3. 通用减七选择器只显示三个音响，同时不丢失任何构造/功能用途。
4. 点击任意和弦默认以自由解释进入时间轴，并按实际音响发现跨章节解释；用户可随后锁定线路。
5. 增三、增六能沿同一协议增量接入。
6. 原有和弦列举、求解、综合练习与禁忌表不回归。
7. 详情明确展示功能替代关系，并用动态构造式与谱例解释具体线路。

## 2. 依赖关系

```text
R0 文档与基线
  └─► R1 身份/知识契约
        ├─► R2 选择投影 + 减七纵切
        │     └─► R4 自由练习接线与存储迁移
        └─► R3 通用详情 UI ───────────────┘
                    └─► R4A 全局音响解释与 schema v5
                              └─► R4B/R4C 功能、构造与章节谱例补完
                                        ├─► R5 增三章节
                                        ├─► R6 增六章节
                                        └─► R7 复用、清理与发布
```

R4A 取代 R4 中“普通和弦直提、游移和弦两步提交”的分流，但保留 R1–R4 已建立的 typed id、
知识目录和 UI 组件。R4B/R4C 先补齐详情内容语义，R5、R6 再沿同一呈现协议扩展和弦家族。

## 3. R0：基线锁定与资料清单

### 工作

- 给 prototype 字段建立“保留 / 扩展 / 舍弃”对照，不复制硬编码数据。
- 为当前章节知识建立盘点表：可结构化、只有 raw trace、尚缺原著依据。
- 固定首版来源格式：`sourceId + edition + chapter/topic + locator`。
- 记录当前大小调目录快照、减七专门练习选择数和全部 usage id。
- 明确自由练习 schema v3 fixture 与 `chordIdentity` 消费点。

### 测试/产物

- theory fixture 列出 C 大调与 A 小调当前目录 identity。
- 保存三个减七音响及其全部 usage 的 golden 数据，后续不得减少用途。

### 退出条件

- 每个首版详情字段都有唯一数据所有者。
- 缺少可靠资料的字段标记为“暂不展示”，没有占位理论结论。

## 4. R1：身份与章节知识契约 ✅

### 工作

- 新增 `VagrantChordFamilyId`、`SoundingClassId`、`ConstructionRouteId` 和
  `ChordInterpretationRef`。
- 新增 typed `ConstructionOperation`、`TheorySourceRef`、`TendencyToneDetail` 与连接引用。
- 定义 `ChordKnowledgeChapterProvider`、贡献 DTO、发现边界和目录校验。
- 实现 `ChordKnowledgeCatalog.resolve(query, context)`；查询同时覆盖确切解释和待确认音响，
  结果保持不可变、UI-neutral。
- 为 raw `ConstructionTrace` 提供迁移适配，但禁止 UI 依赖字符串。
- 规定无详情、重复 id、悬空 interpretation/treatment/source 的诊断行为。

### 代码落点

| 范围 | 文件/目录 |
|------|-----------|
| 基础身份 | `theory/.../harmony/HarmonyConstruction.kt` |
| 知识协议 | `theory/.../harmony/ChordKnowledge.kt`（新） |
| 收集查询 | `theory/.../harmony/ChordKnowledgeCatalog.kt`（新） |
| JVM 发现 | `theory/src/jvmMain/.../harmony/` |
| 测试 | `theory/src/commonTest/.../harmony/ChordKnowledgeCatalogTest.kt` |

### 测试与退出条件

- id 非空且作用域内唯一；引用能解析到同一 `ChordCatalogEntry`。
- 章节不能引用不属于其构造结果的 interpretation。
- 选择目录与知识目录消费同一 vocabulary 时，拼写和解释 id 完全一致。
- common 测试可注入 provider，不依赖 JVM 反射。
- 至少一个自然和弦与一个减七 usage 能解析为完整 detail model。

## 5. R2：选择投影与减七纵切 ✅

### 工作

- 给 `ChordCatalogContribution` 增加默认 `ByInterpretation` 的选择投影。
- 扩展 `ChordSelectionChoice`，返回选择 id、解释引用列表和线路确认状态。
- 实现家族内 `BySoundingClass`，key 为 `familyId + sorted pitch classes`。
- 减七贡献改用 sound-class 投影，复用 `RootlessDominantNinthType.chordId`。
- 把省略根音、下降成员、局部导音转换为 typed route。
- 让专门练习和通用目录委托同一分组函数。
- 保留 `functionalSymbol` 兼容投影，供 R4 前旧 UI 使用。

### 代码落点

- `theory/.../harmony/ChordSelectionCatalog.kt`
- `theory/.../constraint/RootlessDominantNinthVocabulary.kt`
- `theory/.../schoenberg/SchoenbergDiminishedSeventhChapter.kt`
- `theory/.../harmony/ChordSelectionProjection.kt`（可新建）

### 测试与退出条件

- C 大调与 A 小调的通用减七类别各恰好三个 `SoundingClassId`。
- 展平三张卡的 route 后，集合等于改造前全部 usage id。
- C 大调 `♯2–♯4–6–1` 保留指向 III 与 V 的线路、省略根音和倾向音。
- 等音的增六/属七 fixture 不会跨家族合并。
- 非游移贡献的数量、排序、符号和 identity 与基线相同。
- 达成“3 个音响、N 条线路、0 条用途丢失”，且 `ChordCatalogCollector` 无行为变化。

## 6. R3：通用详情模型与桌面组件 ✅

### 工作

- 增加本地化后的 `ChordDetailUiModel`，与 theory model 分层。
- 在 `apps/desktop-ui-kit` 实现 `ChordDetailPanel` 与可复用 section/card。
- 支持查看和预提交模式；后者支持线路单选与确认回调。
- 公共性质只显示一次，线路卡只显示构造、功能、倾向和连接差异。
- 接入来源、严重度、缺失知识 fallback、滚动与窄宽度布局。
- 使用 SMuFL 时遵守现有 Bravura 测量和 Canvas 基线规范。

### 代码落点

- `apps/desktop-ui-kit/.../components/ChordDetailPanel.kt`（新）
- `apps/desktop-ui-kit/.../components/ChordCatalogPicker.kt`
- `apps/desktop/.../ui/harmony/ChordDetailUiMapper.kt`（新）
- `apps/desktop/.../i18n/ExplorationStrings.kt` 及中英文实现

### 测试与退出条件

- 覆盖单/多线路、无详情、长来源、窄栏、滚动与 100%/150% 缩放。
- 多线路未确认时主操作禁用；切线路不直接修改工作区。
- UI 源码不出现 `DIMINISHED7`、`MODAL_AUGMENTED` 等章节特例。
- DTO 可完整渲染 prototype 层级和新增理论区域；截图评审通过。

## 7. R4：自由练习接线与 schema v4 ✅

### 工作

- 右栏以解释引用查询知识目录，和声选择改为“音响卡 → 线路 → 应用”。
- `WorkspaceHarmonySlot.chordIdentity: String?` 迁为可序列化的确切解释引用。
- 新增 v3 → v4 迁移：旧符号在槽位调性中查询唯一解释；多义/失效时保留 legacy symbol 并
  产生可见诊断，不静默选第一条。
- 钢琴卷轴投影、反馈、续写和快照统一从解释引用解析。
- 右栏 tab、展开状态和预览 `SoundingClassId` 保持 UI 瞬态。

### 代码落点

- `theory/.../freepractice/HarmonyWorkspace.kt`
- `exploration/.../FreePracticeDocument.kt`
- `apps/desktop/.../ui/exploration/FreePracticeWorkbench.kt`
- `apps/desktop/.../ui/exploration/FreePracticePlanPanel.kt`
- `apps/desktop/.../ui/exploration/FreePracticeFeedbackPanel.kt`
- `apps/desktop/.../ui/exploration/FreePracticePianoRollProjection.kt`
- `apps/desktop/.../service/FreePracticeFileSnapshot.kt`

### 测试与退出条件

- v1/v2/v3 fixture 均迁到 v4，保存 round-trip 稳定。
- 普通旧符号得到唯一解释；多义符号不被任意解释。
- 切换减七线路时 pitch classes 不变，解释、拼写和规则上下文改变。
- 插入、替换、撤销、重做各只有一个历史事务。
- 自由练习不包含构造说明、目的地硬编码或按符号重复查找。

## 8. R4A：全局音响解释与 schema v5 ✅

### 工作

- 保持 `ChordSelectionCatalog` 门类与卡片投影不变，为 choice 增加 origin 与全局 audible key。
- 构建 `ChordCatalogSnapshot` 和跨贡献 `ChordInterpretationDiscoveryIndex`；查询不再由 UI 传
  `candidateRefs`。
- 增加章节共享顺序元数据和显式 `ChordExplanationId`，把同类公共详情与多条 route 分层。
- 所有和弦统一使用“选卡并立即自由写入 → 看解释 → 可选锁定路线”状态机，删除
  `requiresRouteSelection` 对提交分支的控制。
- `WorkspaceHarmonySlot` 迁到 `WorkspaceChordChoice`，保存 pitch classes 源值与可空 pinned ref。
- 未锁定求解逐解释展开并发布 `effectiveInterpretationRef`，不得默认第一项或合并规则。

### 代码落点

- `theory/.../harmony/ChordSelectionCatalog.kt`
- `theory/.../harmony/ChordKnowledge.kt`
- `theory/.../harmony/ChordKnowledgeCatalog.kt`
- `theory/.../harmony/ChordInterpretationDiscoveryIndex.kt`（新）
- `theory/.../freepractice/HarmonyWorkspace.kt`
- `exploration/.../FreePracticeDocument.kt`
- `apps/desktop/.../ui/exploration/FreePracticeEditorPanel.kt`
- `apps/desktop-ui-kit/.../components/ChordDetailPanel.kt`

### 测试与退出条件

- 除章节显式声明的命名子集拆分外，既有卡片投影稳定；减七仍恰好三个选择卡。
- 从副属七入口能发现同 pitch-class 的增六解释；所选门类解释排第一，其余按章节顺序。
- 减七同一解释只显示一次，所有目标音级 route 完整且稳定排序。
- 普通、减七、增六点击后都立即以自由解释进入时间轴，锁定/解除锁定各自形成一次替换事务。
- v4 exact ref 迁到 v5 pinned choice；自由 choice 保存、读取、撤销、重做稳定。
- 未锁定搜索结果等于逐个锁定候选结果的集合，规则没有跨解释污染。

## 9. R4B–R4C：功能、构造与章节谱例补完 ✅

### 实施结果

- `ChordExplanationDefinition` 已保留公共功能；route 的 typed 替代关系由 registry 校验，减七显示属/副属替代，
  拿坡里通过专属 `NEAPOLITAN → DIATONIC_PREDOMINANT` 显示属前替代，通用小下属关系不继承该结论。
- 属九基础定义拥有名称与 `V9` 符号，减七只引用定义并传入目标音级；同一 tone 列表生成动态标题和谱例，
  C 大调主属/五级副属分别输出 `属九和弦V9 (5-7-2-4-b6)…` 与 `五级副属九和弦V9/V (2-#4-6-1-b3)…`。
- 谱例复用 `SimpleScoreView → RuntimeScore → ScoreRenderer`，自动选择加线最少的连续八度，并把谱线、谱号、
  音头、临时记号和加线共同纳入适配边界；省略根音按 `VoiceNoteSection` 降为灰色。
- 中古调式章节产出完整七音级与和弦音标记，用全音符列出音级并灰显非和弦音；小下属章节保留 -3/-4 调号
  双和弦示意，并用章节 `namedSubsets` 单列拿坡里；自由练习只消费通用分组与构造属性。

### 工作与验收

- 解释组保留一致的基础功能；线路增加 typed 功能关系，由章节与 `HarmonicTreatmentRegistry` 解析，UI
  不扫描 constraint 文案。减七明确显示“属功能；替代属/副属和弦”，并带当前目标音级。
- 可扩展 sealed 构造呈现协议已覆盖省略成员、中古调式音级和小下属双和弦；各 variant 携带自己的 typed
  参数，后续增六继续新增专属 variant，不共用硬编码句式。
- C 大调导减七线路必须生成“属九和弦V9 (5-7-2-4-b6) 省略根音 (5)”；同一 DTO 生成紧凑五线谱，
  省略根音灰色、实际发声音为正常色，并注明这是结构示意而非转位或四部排列；绘制复用现成 renderer。
- UI 采用“公共摘要 → 单层可选路线 → 选中路线详情”的层级：路线用单选语义、选中描边和短标签明确
  交互范围，不再嵌套解释卡；展开内容按“功能替代 → 倾向音/连接 → 动态谱例”组织。
- 测试显式覆盖减七 treatment 替代链、主属与副属动态文案、灰色省略根音、拿坡里独立且不重复，及其仅在
  显式关系后显示“替代属前”；UI 无 `DIMINISHED7`/`NEAPOLITAN` 分支，命名子集保持通用投影。

## 10. R5：增三游移和弦章节

### 工作

- 从 `SecondaryHarmonyVocabulary.MODAL_AUGMENTED` 提取共享 `AugmentedTriadVocabulary`；
  原次级和声章节改为委托。
- 增加 family contribution、四个 sound class 与全部调式/音级 route。
- 补对称性、等价构造根音、增五度预备/解决和媒介领域说明。
- 综合练习保持统一大小调入口，复用已有增五度 typed requirement。

### 测试与退出条件

- 十二平均律中恰好四个可听增三类别。
- 展平 route 后与抽取前 `MODAL_AUGMENTED` 用途等价。
- 切换来源领域不改变 pitch classes，但改变解释和连接信息。
- secondary-harmony 枚举/求解无回归，无复制 vocabulary 或重复 finding。

## 11. R6：增六游移和弦章节

### 资料与实现

- 固定《和声学》版次与章节定位，逐类列出构造、拼写、根音、倾向音和连接例。
- 区分原著说明、项目推导与产品推荐，未确认连接不实现为规则。
- 新增 `AugmentedSixthVocabulary` 与 `SchoenbergAugmentedSixthChapter`。
- 为增六、增六五、增四三、增二建立 recipe、interpretation、typed role 和 route。
- 章节显式声明 family-scoped 聚合；与属七等音时保留独立拼写和 treatment。
- 接入独立练习与统一综合入口，不拆分大小调 exercise id。
- 硬规则使用可投影 typed requirement；软连接只进 scoring/finding。

### 测试与退出条件

- 每类 recipe 的拼写、音程和倾向音 golden。
- 等音属七与增六拥有不同身份，详情可说明关系。
- 不同转位/目标领域的进入与解决规则得到覆盖。
- enumerate 与 solver 共享硬判定/软评分本体。
- typed 规则变化时重刷禁忌表并通过一致性测试。
- 每类增六均有可追溯构造、确切解释、连接规则和来源。

## 12. R7：复用、性能与清理

### 工作与验收

- 在和弦分析插件验证复用 `ChordDetailPanel`，只增加宿主 adapter。
- 缓存 key 使用 `InterpretationId + tonal-context id + knowledge revision`。
- 上下文连接分析在后台生成不可变帧；右栏关闭时不扫描全谱。
- 删除 raw trace UI adapter、旧 `chordIdentity` 查找和重复 i18n 文案。
- 自由练习与分析插件对同一解释显示相同公共详情。
- provider 发现、目录构建和解析有可解释耗时，无 Compose 主线程全谱工作。
- 全量测试、桌面构建、截图与性能回归通过，并更新相关文档实施状态。

## 13. 发布切片与回退

1. **减七基础切片**：R1–R3；新目录/详情先在测试页验证，旧自由练习仍工作。
2. **自由练习旧切片**：R4；schema v4 作为可迁移基线，不继续扩展其特殊提交流程。
3. **统一解释切片**：R4A；开启全局发现、自由解释与 schema v5。
4. **详情语义切片**：R4B；先交付减七动态功能/构造，再作为其他特殊和弦的接入门槛。
5. **章节扩展切片**：R5、R6 分别发布，互不阻塞。

兼容期保留 `functionalSymbol` 显示字段。锁定状态以解释引用为真相，自由状态以 pitch classes 为
真相。回退只能关闭新 UI 入口，不能把 v5 自由选择降级成任意解释；发布前验证旧版本拒绝提示。

## 14. 完成定义

- 章节是知识和规则的唯一所有者，知识目录是唯一聚合入口。
- 选择 origin、可听音响、解释组、构造路线和精确解释使用明确且不混淆的身份轴。
- 减七 3 个、增三 4 个可听选项成立，全部构造线路可见且可选择。
- 功能替代与构造参数来自 typed 章节本体；谱例能区分省略成员与实际发声音。
- 增六保持拼写与功能语义，不被全局等音合并。
- 原著依据、项目推论和产品推荐在数据与界面上可区分。
- 没有自由练习专用百科、字符串规则解析或 Renderer 音乐逻辑。
- 文档、迁移、i18n、单元/集成/截图/性能测试全部同步完成。
