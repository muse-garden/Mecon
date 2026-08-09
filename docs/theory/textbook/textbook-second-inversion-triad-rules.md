# 教材三和弦第二转位规则

> 代码入口：
> `theory/src/commonMain/kotlin/com/mecon/theory/textbook/SecondInversionTriadRules.kt`、
> `theory/src/commonMain/kotlin/com/mecon/theory/textbook/TextbookTriadWritingSolver.kt`

## 1. 适用范围

这批规则覆盖教材语境中的三和弦第二转位，也就是四六和弦。第二转位不作为第一转位那样的自由低音线条资源，而是要求放进明确上下文：

- 终止四六：`I(46)-V`，通常继续到 `I`。
- 经过四六：低音按级进通过中间的四六和弦。
- 持续音四六：在持续低音上装饰原位三和弦。
- 同和弦转位插入：相同和弦的不同转位之间可插入四六形态。

这些上下文至少需要观察三个和弦，终止四六可在 `I(46)-V` pair 上先识别，探索模式仍把示例扩成 `I(46)-V-I`。

## 2. 纵向配置

`checkVerticality(verticality, triad, key)` 只在低音为五音时应用第二转位纵向规则：

- 返回 `SECOND_INVERSION_DECORATION` indication，说明该和弦应按装饰性四六理解。
- 入门约束 preset 会把完整三和弦、重复低音与避免重复导音编译为生成期约束。
- 四声部优先重复低音，也就是该三和弦的五音。
- 仍不应重复导音。

## 3. 上下文规则

`checkContextualUses()` 在完整候选上判断第二转位是否有标准语境：

- `CADENTIAL_SIX_FOUR`：主和弦第二转位接原位属和弦。
- `PASSING_SIX_FOUR`：当前第二转位在三和弦中间，低音两侧同向级进。
- `PEDAL_SIX_FOUR`：前后为同一个原位三和弦，低音保持，当前为第二转位装饰和弦。
- `SAME_CHORD_INVERSION_INSERTION`：前中后三个和弦 pitch-class 相同，中间为第二转位。
- 不能归入上述用法时，完整候选产生 `UNSUPPORTED_SECOND_INVERSION` HARD violation。

经过与持续音四六会额外用 `UPPER_VOICE_LEAP` 标出上方声部跳进，因为这些用法应尽量平稳连接。

## 4. 探索模式

`TextbookTriadWritingSlot.secondInversion()` 固定第二转位；
`rootFirstOrSecondInversion()` 用于四六练习的开放槽位。`ProgressionRequest.policyId = "second-inversion-triads"` 时：

- 输入少于三个和弦会补成三和弦语境；
- 最后一和弦不枚举第二转位，避免孤立结尾四六；
- 规则示例会按目标规则扩成三槽，如终止四六 `I(46)-V-I`、持续音四六 `I-IV(46)-I`。

## 5. 测试约定

新增第二转位规则时至少覆盖：

- 一个纵向配置或低音重复检查；
- 四种标准用法之一的正向 indication；
- 一个孤立或无法归类的第二转位 HARD violation；
- 若接入探索模式，覆盖三和弦扩展与 `second-inversion-triads` policy。

