# 转调工具与第一套转调练习

> 状态：五度圈、自然三和弦公共和弦查询、双向筛选与第一套共同和弦转调练习已实现。
> 可组合路线与技术 DAG 正在实施；拿坡里与增六词汇已接入勋伯格章节，完整的跨调路线编排仍为 🚧。

## 1. 入口与分层

- theory 查询：`theory/.../Modulation.kt`
- 勋伯格练习编译：`theory/.../schoenberg/SchoenbergModulation.kt`
- exploration 请求：`ModulationExerciseCellRequest`
- 桌面 UI：`apps/desktop/.../exploration/ModulationTool.kt`
- 共享五度圈：`apps/desktop-ui-kit/.../components/CircleOfFifthsPicker.kt`

公共和弦、功能和相对音高都在 theory 层计算；Compose 只负责选择与展示。练习编译为现有
`ConstraintProgram`，自由与勋伯格模式共用候选空间、四部写作规则、finding 和搜索器。
探索页与和弦分析插件的复调调性区域编辑器共用同一无 theory 依赖的五度圈组件；调用方只
负责把 `fifths + mode` 与自己的调性模型互转。

## 2. 五度圈与公共和弦

`ModulationKey` 用 `fifths + KeySignatureMode` 保留 `C♯/D♭` 等等音调的调号拼写。
五度圈外圈显示大调，内圈显示关系小调，覆盖 `-7..7` 个升降号。几何上使用 12 个音高位置，
`C♭/B`、`G♭/F♯`、`D♭/C♯` 及对应关系小调共享位置；同一个圆形按钮按左右半圆分别显示、
点击两种拼写。数据模型仍保留 15 种调号拼写，不能因等音合并而丢失记谱方向：

- 共享选择器使用紧凑圆形按钮；内圈交替调整半径并做小写字高的光学校正。交错幅度在上下
  方向减弱，避免底部节点被压成水平排列；调性仍与对应外圈保持同一条由圆心出发的辐射方向。
- `ModulationPitchLabels.relativeTonicLabel(referenceKey, targetKey)` 是调与调之间相对主音标签的
  公共入口；五度圈相对显示与自由练习调性线必须共用该方法，不在 UI 内重复推导音级。
- `ModulationCircleOfFifths.signedDistance(referenceKey, targetKey)` 返回五度圈最短有向距离，
  顺时针为正、逆时针为负；六步等距时保留原始方向。`signedDistanceLabel` 负责为正数补 `+`。
- `ModulationKey.circlePosition` / `isEnharmonicWith` 明确区分“12 个发声位置”和“15 种调号
  拼写”；候选排序按前者计算，因此 `B ↔ C♭` 为 0 步，但相对音级与和弦拼写仍按后者显示。
- 绝对音高：显示调名与每个和弦的拼写构成音；
- 相对音高：每个调名显示为其主音相对当前调的级数，和弦行显示该调内的相对构成音。

`ModulationCommonChordCatalog.commonChords(keys)` 对所选调的实际三和弦身份
（根音 pitch class + quality）求交集，再为同一和弦附上每个调的独立解释：

- 调内级数 / 罗马数字功能；
- 依照该调拼写的构成音；
- 每个构成音在该调中的相对音高。

`keysContaining(chordIds)` 执行反向交集查询。当前词汇来自 `NaturalTriads`；增加新和弦族时，
应扩展候选提供者及功能标签，不在 UI 中加入和弦名称特判。

路线编辑使用 pairwise 查询，而不是把所有已选调一次性求共同交集：

```kotlin
commonChords(source, target)
nextKeys(source, pivotChordId)
transitionsFrom(source)
```

每条边只要求 pivot 同时属于相邻两个调的指定 `ChordVocabularyId`。当前默认词汇为
`NATURAL_TRIADS`；词汇 ID 随路线保存，未来扩展半音化词汇不会改变旧路线语义。

## 3. 可组合路线

固定 `±3/±4` 路线降级为可编辑教学预设，运行时本体为：

```kotlin
data class TonalRoutePlan(
    val source: ModulationKey,
    val steps: List<CommonChordPivotStep>,
    val endingIntent: TonalEndingIntent,
    val techniques: List<TonalTechniqueSelection>,
)
```

每一步的源调由前一步结果决定，保存目标调、共同和弦身份和词汇。用户可连续添加多次共同
和弦边，到达更远调性；同名大小调只是同一模型下的关系分类，不使用独立编译器。

`TonalEndingIntent` 分为：

- `OPEN_FRAGMENT`：允许停留在不稳定或未确认的调性片段，不强制最终终止式；
- `LIGHT_CONFIRMATION`：目标调特征音与基本 `V–I`；
- `ESTABLISHED`：在 LIGHT 上叠加用户选择的强化技术。

旧 `TonalPathTemplate.expectedFifthsDelta` 只作为教学预设的验收 metadata，不再限制通用路线。

## 4. 技术 DAG

调性路线是当前乐谱的一条有序链；教学模式依赖另用无环 `TonalTechniqueGraph` 表达。
每个技术节点集中声明 prerequisites、conflicts、applicability、recommendation predicate、
compiler factory 与 i18n key。

共同和弦路线建立后，用户可选择开放结束或目标调确认；确认技术包括终止式与持续音。
检测到同主音 `MINOR → MAJOR` 的路线边时，系统建议采用持续音加强目标大调，但不自动启用、
也不把忽略建议判为非法。接受后复用 `HarmonicTexturePlan` 编译实际持续声部、释放点与
`V–X–V` pattern。

## 5. 第一套练习

`SchoenbergModulation.compile` 构造以下槽位：

```text
原调 I → 原调进行… → 所选共同和弦 → 目标调进行… → V → I
                       ↑ 双重解释          ↑ 至少一个原调音阶外音
```

硬条件：

1. 开头为原调主和弦；
2. 转调槽固定为所选公共和弦解释，其 `compatibleContextIds` 同时覆盖源调与目标调；
3. 共同和弦后的目标调区域至少一个和弦含原调音阶中没有的 pitch class；
4. 最后两个槽为目标调原位 `V/V7-I`，终止主和弦为原位；小调的属三 / 七和弦必须使用
   升高导音（例如 a 小调使用 E 大三和弦 / E7，含 G♯）；
5. 用户可分别设置公共和弦前、后的和弦数；后者包含终止式，最少为 2。

自由模式使用自然三和弦词汇并只叠加通用自由古典规则。勋伯格模式复用
`SchoenbergIntegratedTechTree` 的完整章节词汇与规则：三和弦全部转位、七和弦全部转位、
完整性 / 重复避免、共同音、导和弦与七音预备解决、小调旋律规则，以及根音方向、下降进行
补偿、类似和弦/进行反复、旋律极值与旋律反复。各规则分别投影到源调和目标调的稳定区域；
跨越共同和弦的单个连接不套用单一调性的音级差，避免用错误坐标解释根音方向。带源 / 目标
双重解释的公共和弦目标只允许出现在转调槽，不能泄漏到目标调正文并沿用源调级数。

长练习采用两阶段搜索：先按共享的勋伯格符号规则做分层束搜索，按不同槽位轮流放入七和弦
或转位，并用 `SchoenbergForbiddenTransitions` 的转位敏感禁忌表排除必然无法四部实现的
相邻对；再把每条候选固定为单目标槽，依次交给通用 SATB 求解器。候选按“终止 V7、其他
七和弦、仅转位、纯根位”交错，既不会让某一类难配写和弦占满预算，也不会在首条失败后把
章节词汇全部丢掉。终止 `V/V7-I` 在符号域即收窄，不等到完整路径才检查。这样
`5 + 共同和弦 + 5` 等长练习不会让开放的和弦选择与每槽四部排列在同一 DFS 中相乘。

“目标调区域至少出现一个变化音”使用窗口内存在量词。搜索前缀中较早和弦尚未含该音时，
只要窗口内未来槽仍有可匹配目标就保持未定，不能提前判为违反。

谱例输出沿用 SATB 装配器，但不按固定 4/4 分组。公共和弦仍留在原调小节，目标调第一个槽
固定映射到下一小节起点，调号变化写在同一边界。例如前后各 5 个和弦时，内部小节长度为
`6/4 + 5/4`（第一小节含 5 个原调和弦与公共和弦）；这些拍号只负责时值与小节线计算，
`showTimeSignatures=false` 使 Computed 层不生成可见拍号。

## 6. 测试

- `theory/.../ModulationTest.kt`：公共和弦解释、反向查询、变化音、小调升高导音终止式、
  勋伯格完整词汇、最终解中的 V7 / 转位，以及 C 大调到 G 大调所有自然三和弦枢纽的长练习
  符号候选；
- `theory/.../constraint/ConstraintProgramSolverTest.kt`：多槽 `TargetMatches` 不误剪未来匹配；
- `exploration/.../ExplorationRequestRunnerTest.kt`：请求到可渲染谱例、目标调号变化，以及
  C 大调经 G 大三和弦转 G 大调、前后各 5 个和弦的端到端回归。
- 可组合路线测试：每条边的双重解释、连续多次 pivot、开放结尾、旧模板等价迁移；
- 技术图测试：全图无环、前置条件完整、同名小调到大调产生非强制持续音建议。
