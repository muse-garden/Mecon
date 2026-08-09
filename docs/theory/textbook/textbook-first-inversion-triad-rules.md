# 教材三和弦第一转位规则

> 代码入口：
> `theory/src/commonMain/kotlin/com/mecon/theory/textbook/FirstInversionTriadRules.kt`、
> `theory/src/commonMain/kotlin/com/mecon/theory/textbook/TextbookTriadWritingSolver.kt`

## 1. 适用范围

这批规则覆盖教材语境中的三和弦第一转位，也支持与原位三和弦混合写作。调用方需要提供：

- `Key`
- 当前 `NaturalTriad`
- 当前 `FixedVoiceVerticality`
- 连接检查所需的前后 `NaturalTriad` 与 `FixedVoiceTransition`
- 当前练习使用的 `TextbookTriadConstraintPreset` 与 `RuleProfile`

第一转位的主要教学目的不是替代原位和弦，而是丰富低音线条。因此自由练习可通过
`TextbookTriadWritingSlot.rootOrFirstInversion(triad)` 允许同一音级枚举原位与第一转位；
旋律规则和四部和声规则再共同决定哪种低音更合适。

## 2. 纵向配置

`checkVerticality(verticality, triad, key)` 只在低音为三音时应用第一转位纵向规则：

- 返回 `FIRST_INVERSION_BASS_LINE` indication，说明第一转位用于丰富低音线条。
- 入门约束 preset 会把完整三和弦与避免重复导音编译为生成期约束。
- 重复音可依据音响效果自由选择，不要求重复根音。
- 与原位和弦一致，不应重复导音。

减三和弦位置通过 `checkDiminishedTriadPosition()` 独立检查：

- 低音为三音时返回 indication。
- 低音不是三音时返回 `HARD` violation，因为古典主义时期减三和弦几乎只使用第一转位。

## 3. 连接禁则

`checkTransition()` 当前接入一条可试听的错误规则：

- 大调中，原位属和弦之后不能接六级小和弦。

该规则要求大调中前一和弦为原位 V，后一和弦为第一转位的 vi 小三和弦。探索模式把它注册为
`demonstrableAsViolation`，可编译为 `REQUIRE_VIOLATION`，用于生成错误规则试听。

## 4. 写作求解器

`TextbookTriadWritingSolver` 是含第一转位练习的薄适配器：

- `TextbookTriadWritingSlot.rootPosition()` 固定原位。
- `TextbookTriadWritingSlot.firstInversion()` 固定第一转位。
- `TextbookTriadWritingSlot.rootOrFirstInversion()` 在同一时间槽枚举两种低音位置，供自由练习混用。
- 原位 slot 仍委托 `RootPositionTriadRules`。
- 第一转位 slot 委托 `FirstInversionTriadRules`。
- 四部和声与旋律规则继续复用 `FourPartTextbookWritingRuleProvider` 与
  `MelodyTextbookWritingRuleProvider`。

探索模式默认的原位进行不变；`ProgressionRequest.policyId = "free-triads"` 时可启用原位与第一转位混合枚举。

## 5. 测试约定

新增第一转位规则时至少覆盖：

- 一个第一转位正向 indication；
- 一个导音重复或和弦外音/缺音违规；
- 一个减三和弦正向和反向位置检查；
- 一个连接禁则；
- 若规则进入探索模式，覆盖 `RuleCatalog` 输入约束与 `REQUIRE_VIOLATION` 生成路径。
