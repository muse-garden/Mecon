# 小调分支与无共同音连接

小调综合练习复用 `SchoenbergIntegratedTechTree` 的 `program` / `enumerate` 骨架，按
`key.mode == AEOLIAN` 分支追加 `SchoenbergMinorChapter` 的规则族。规则一律用不限 arity /
转位的 typed requirement 表达，落在被禁忌表探测器投影的字段。

## 旋律进行硬要求

`minorMelodyNeighborRequirements` 要求升六保持或上行级进到导音、导音保持或上行级进到主音；
自然第 6 / 第 7 级禁止进行到变化音。规则按源音级 PC 选择，并逐源和弦音角色发射，覆盖导音
作为 V 三音、vii° 根音或 III+ 五音等情况。

保持属于合法处理：`allowedDiatonicStepDeltas = {0, 1}`。导音候选用
`degree1+alt{-1,0}` 编码，避免把自然七级误收进候选。`sourceSelector` 必须保留，否则关系剪枝
会把规则误挂到不含源音的和弦上。

## 减增和弦与七和弦

`minorDiminishedNeighborRequirements` 和 `minorAugmentedNeighborRequirements` 按 quality
选择，不限定度数。减五度同声部预备并下行解决；增五度同声部预备，其上行解决由统一的
导音规则拥有。`minorDissonanceAvoidDoublings` 禁止重复减/增五音，
`minorEssentialFifthCompleteness` 要求五音在场。

小调七和弦词汇排除七音为升六或导音的和弦，避免七音下行与变化音上行规则冲突。

## 无共同音连接

`SchoenbergNoCommonToneChapter` 复用综合骨架，但移除 `AdjacentCommonToneRequirement`。
词汇只保留协和大、小三和弦；减、增和弦的不协和音预备与“无共同音”要求矛盾。平行五、八度
仍由求解器通用规则检查，小调继续应用旋律硬要求。

## 小调禁忌表

小调使用独立的 `forbidden-transitions-minor.txt`，由
`SchoenbergMinorForbiddenTransitionGeneratorTest` 在 a 小调探测。修改小调 typed rules 后运行：

```shell
./gradlew.bat :theory:schoenbergForbiddenTransitionTest --tests "*SchoenbergMinorForbiddenTransitionGenerator*" -Pschoenberg.forbidden.write=true
```
