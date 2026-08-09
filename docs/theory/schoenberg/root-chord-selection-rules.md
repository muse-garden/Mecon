# 根音与和弦选择规则接入

> 适用范围：勋伯格练习中所有只依赖和弦目标或根音序列的规则。总览见
> [schoenberg-harmony.md](schoenberg-harmony.md)，约束分层见
> [../constraint-architecture.md](../constraint-architecture.md)。

## 1. 先判断规则属于哪一层

| 规则性质 | 例子 | 正确接入点 |
|---|---|---|
| 词汇 / 单槽硬限制 | 是否允许七和弦、某转位 | 共享 vocabulary builder → `SlotDomain` |
| 符号序列硬限制 | 下降进行须补偿、类似进行不可反复 | 一个 sequence policy；enumerate 剪枝 + runtime `Constraint` |
| 符号序列软偏好 | 超越进行谨慎使用、同根音回返距离 | 一个 scoring policy；enumerate 与 solver 共同计分 |
| 四部写作硬可行性 | 七音解决后无可用声部、必出平五 | typed requirement → 求解器探测 → forbidden-transition 数据 |
| 四部写作软偏好 | 声部平顺、排列、旋律反复 | solver score；除非存在无声部信息的符号级投影，否则不进 enumerate |

不要把“教学上不喜欢”写成禁忌表，也不要把“四部永远写不出”写成 enumerate 启发式。

## 2. 单一判定本体

根音 / 和弦选择的硬规则若只依赖 `SchoenbergSymbolicChord`，应先定义不可变 policy 或纯函数，
输入只包含调性与符号序列，输出合法性或结构化违规：

```kotlin
data class ChordSequencePolicy(/* immutable parameters */) {
    fun evaluate(slots: List<SchoenbergSymbolicChord>): SequencePolicyResult
}
```

- enumerate 在追加候选时调用同一 policy 的前缀入口做增量剪枝，并在完整序列上再校验一次。
- `ConstraintPredicate` 在 realization 后从 `ChordTarget` 还原同一根音 / 和弦特征，再委托该 policy。
- 根音方向与相似性直接读取 `ChordTarget.degree`，禁止用根音 `PitchClass` 在自然音阶中
  `indexOf` 反推；小调 `vii°` 的 G♯ 等变化根音不在自然小调音集内，但符号音级仍明确为 7。
- 相似和弦的身份维度必须由 policy 集中定义；当前“类似”只看根音音级，因此转位、三 / 七和弦与
  同根音不同 quality 都不能绕过规则。
- selector 只限定教材真正要求的轴；规则适用于所有自然音和弦时，不添加 arity / inversion 限制。

若暂时不能让两侧直接调用同一函数，至少共享同一参数对象并用等价性测试锁住；不得复制魔法数字。

## 3. 单一评分本体

符号序列软偏好必须实现为一个纯评分策略：

```kotlin
data class RootProgressionScoringPolicy(/* all weights */) {
    fun score(rootDegrees: List<Int>): RootProgressionScore
}
```

当前参考实现是
`theory/src/commonMain/kotlin/com/mecon/theory/constraint/RootProgressionScoring.kt`；
唯一章节配置为 `SchoenbergRootMotionAndRepetitionChapter.ROOT_PROGRESSION_SCORING_POLICY`。

接入规则：

1. 所有权重、距离公式与分项定义只放在 scoring policy；章节、enumerator、provider 不再声明副本。
2. enumerate 用 `policy.score(prefix).total` 排列下一和弦，低分优先贪心展开；最终候选仍用同一 total 排序。
3. solver 通过 `ConstraintPredicate.RootProgressionPreference(policy)` 调用相同 `score`，其
   `RuleFinding.scoreDelta` 必须等于 enumerate 对同一符号进行算出的 total。
4. solver 总分 = 共享和声分 + realization 分。声部运动、音域、平行进行、排列与旋律形态仍由 solver 追加。
5. finding 应展示分项（超越次数、连续超越次数、类似和弦距离成本），便于调权重和解释排序。

禁止以下写法：

- enumerate 中 `+10`，solver 中另写 `Prefer(weight=4)`；
- 先按无分数 DFS 截断到 `maxResults`，再对已截断结果排序；
- 为同一偏好既保留逐连接软约束，又添加全局 scoring predicate，造成重复计分；
- 用转位或 arity 改变相似和弦身份，从而让 `I-ii-…-I6-ii6` 逃过反复检测。

## 4. 搜索与剪枝顺序

推荐顺序：

1. vocabulary / selector 排除不在章节词汇内的目标；
2. forbidden-transition 排除四部写作已知无解相邻对；
3. sequence policy 排除符号级硬违规；
4. 共享 scoring policy 给合法前缀计分，按低分贪心展开；
5. 收集有界候选池后仍按同一分数截取 `maxResults`；
6. 固定符号进行进入 `ConstraintProgramSolver` 前先做 target-only 硬约束预检；确定违规以 0 节点返回；
7. 同一目标前缀的根音方向、类似和弦与根音评分只求值一次，再叠加四部 realization 成本；
8. “其他进行”在一个总预算内探测多条已排序符号进行，首条无解不能直接结束整个请求；
9. 唯一声部极值若已重复、且剩余目标与音域的乐观上界也无法产生更外侧音高，可在终点前安全剪枝。

评分不能替代硬剪枝；硬剪枝也不能吞掉“合法但较差”的教学偏好。

## 5. 测试卡尺

新增或修改根音 / 和弦选择规则时至少覆盖：

- policy 单元测试：边界音级、跨 7 循环、完整与前缀序列；
- 大调与小调 enumerate 均非空，结果全部满足硬 policy；
- 同根音不同转位、三和弦 / 七和弦、同根音不同 quality 的相似性；
- 结果的共享和声分单调不降；
- 同一固定进行：`enumerationScore.total == RootProgressionPreference finding.scoreDelta`；
- 旧独立权重 / 重复 predicate 不存在；
- 若修改 typed 硬规则，按 `AGENTS.md` 重刷并校验大 / 小调 forbidden-transition 表。

常用验证：

```powershell
.\gradlew.bat :theory:jvmTest --tests "*SchoenbergRootMotionAndRepetitionChapterTest*"
.\gradlew.bat :theory:jvmTest --tests "*Schoenberg*"
.\gradlew.bat :exploration:jvmTest :apps:desktop:compileKotlin
```
