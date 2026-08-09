# Exploration 重构审计结论

> 本文保留可复用的架构结论，不再按提交逐条记录迁移过程。当前范围是 exploration 请求编排与 theory 和弦本体统一；free-practice Web 迁移不属于本文范围。

## 当前结论

- exploration 请求已按能力拆分为规则示例、进行、勋伯格与转调等 runner；请求 DTO 通过 `selections` 和 descriptor 声明能力，避免在 runner 中按 exercise id 堆业务分支。
- 和弦路径统一为 `ChordRecipe → ConstructedChord → ChordCatalogEntry → InterpretedChordTarget`。枚举、解释与求解器通过稳定的 typed 能力接口连接，不再维护 `DefinedChordTarget` 的旁路本体。
- 勋伯格章节由 curriculum descriptor、registry、enumerator、program builder 和 vocabulary 协作；typed 规则仍按根目录 `AGENTS.md` 的复用和禁忌表约束执行。
- `SchoenbergExerciseRequest` / `EnumerationRequest` 已使用通用 `selections`；handler 通过 descriptor 的 `handlerId` 绑定，架构测试覆盖 descriptor/handler 对应关系和 treatment 继承。
- 旧的 exploration/theory legacy merge、旧章节快捷 program 与重复 diagnostic 输出已移除。协议兼容字段仍保留时，入口先归一化到 selections map。

## 当前 TODO

1. 将综合练习剩余的布尔开关继续收敛到 `SchoenbergIntegratedStageSpec`、treatment registry 与 vocabulary，避免新增和弦类型需要多处同步。
2. 桌面 exploration UI 完成 selections map 迁移后，删除兼容字段及其归一化代码。
3. 合并 `ConvenienceRequestSpecMapper` 与 `RuleExampleRequestRunner` 中仍重复的选择/requirements 语义，并保留等价性回归测试。
4. 让 descriptor 与 handler 的重复登记在开发期 fail-fast；新增章节只需注册 descriptor 和既有执行模型。

## 需要保持的边界

- 新章节不新增大小调成对 exercise id；调式和选项由统一入口决定。
- 和弦选择规则的枚举剪枝与求解器约束必须委托同一 policy；四部写作导致的无解相邻进行继续由禁忌表探测。
- 兼容读取可以保留旧字段，但新写入只生成 selections；不得让兼容字段重新成为业务分支入口。
- 结构化文档模型只保存源字段与稳定 ID，不把运行时对象引用写入存储层。

## 验收

修改 exploration/theory 编排时至少运行：

```powershell
.\gradlew.bat :theory:jvmTest :exploration:jvmTest
```

涉及桌面请求协议、registry 或渲染结果时，同时运行对应 desktop / renderer 定向测试，并检查 `docs/README.md` 的入口是否仍然有效。
