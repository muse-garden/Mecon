# 脚本引擎（用户 / LLM 代码执行）🚧 设计

> 状态：设计文档，对应 solver-api 里程碑 S3。
> 前置：[../theory/solver-api.md](../theory/solver-api.md) ·
> [../theory/constraint-program.md](../theory/constraint-program.md)。
> 定位：探索模式内执行用户或 LLM 编写的求解脚本；**不是**插件系统的替代
> （插件仍走 Kotlin SPI，见 plugin/）。

## 1. 选型：GraalJS（JS 优先，TS 后置）

- 桌面端是 JVM，嵌入 `org.graalvm.polyglot:polyglot` + `org.graalvm.js:js-community`。
  普通 JVM 上以解释模式运行即可——脚本的主要工作是构造 `ConstraintProgramSpec`，
  不在性能热路径上；谓词逃生舱的预算见 constraint-program §5。
- v1 只接受 JavaScript（ES2022）。TypeScript 支持 = 内置转译（esbuild/swc 二进制或
  纯 JS 转译器）🚧 后置；先发布 `.d.ts` 类型声明供外部编辑器提示。
- Python（GraalPy）不做：体积与启动成本高，且脚本 API 已是 JSON 协议，
  将来若有需求可作为第二宿主接入同一协议。

模块位置：`apps/desktop`（或抽 `:scripting-jvm`），实现 `:theory` 的
`ScriptPredicateHost` 与 `:exploration` 的脚本执行接口。`:theory` / `:exploration`
不依赖 Graal。

## 2. 沙箱

每次运行创建独立 `Context`，统一约束：

- `allowHostAccess = NONE`、`allowIO = false`、`allowCreateThread = false`；
- 语句数上限（`sandbox.MaxStatements`）与墙钟超时（协程超时 + `Context.interrupt`）；
- 内存：v1 依赖语句数上限近似控制，堆隔离 🚧（GraalVM sandbox 选项在社区版受限）；
- 取消：cell 运行取消 → `Context.close(true)`；Context 不跨线程共享，
  执行在 Compute dispatcher。

脚本是本机用户自己（或其授意的 LLM）编写，威胁模型是**防失误不防恶意**：
沙箱目标是防死循环、防误操作文件系统，不承诺对抗性隔离。

## 3. 脚本 API

注入单一全局 `mecon`，与 SolverApi 五入口同构（JSON 进出）：

```typescript
// mecon-solver.d.ts（与 CapabilityManifest 同源生成/同步）
declare const mecon: {
  describe(): CapabilityManifest;
  enumerate(req: EnumerationRequest): EnumerationResult;
  solve(req: SolveRequest): SolveResult;          // 脚本内联试解，预算受限
  program: ProgramBuilder;                        // ConstraintProgramSpec 构造辅助
  registerPredicate(
    name: string,
    scope: "SLOT" | "TRANSITION" | "COMPLETE",
    fn: (view: PredicateView) => boolean | { score: number; message: string },
  ): void;
};
```

- `program` builder 是纯 JS 辅助库（随宿主注入），产出仍是可校验的
  `ConstraintProgramSpec` JSON——半音下行低音例：

```javascript
export default function build() {
  const p = mecon.program.create({ length: { min: 6, max: 10 } });
  p.linePattern("BASS", { anchor: "anywhere" }, (line) => {
    line.note({ var: "X", hold: [1, 3] });
    for (let i = 0; i < 3; i++) line.note({ hold: [1, 3], step: { chromatic: -1 } });
  });
  p.ruleSet("textbook.first-inversion-triad");
  return p.build();
}
```

- 脚本内 `solve` 允许小预算试解（脚本自己迭代收窄约束），宿主对总求解时间设配额；
  最终提交的求解仍由 cell 运行统一执行，保证输出/过期/指纹走同一管线。

## 4. 两种脚本角色

| 角色 | 入口约定 | 产物 |
|------|---------|------|
| 请求构造器 | `export default function build(ctx): ConstraintProgramSpec` | 声明式约束程序（主路径） |
| 谓词 | `mecon.registerPredicate(...)`（可在 build 内注册） | 搜索期回调（逃生舱，`costly` 标注） |

`ctx` 提供当前 cell 的上下文：调性、材料谱只读快照、被 refine 的 baseline。
谓词必须纯函数；宿主按 (predicateId, view digest) memo，见 constraint-program §5。

## 5. Cell 集成

`RequestCell` 的请求类型新增：

```kotlin
@Serializable @SerialName("scripted")
data class ScriptedRequest(
    val source: String,                  // 脚本源码，随文档持久化
    val language: String = "js",
    val key: KeySpec,
    val policyId: String,
    val search: SearchSpec = SearchSpec(),
) : CellRequest
```

- 运行流程：执行脚本 → 得 `ConstraintProgramSpec`（+ 注册的谓词）→ 编译校验
  （结构 + 目录校验，错误以 SolverDiagnostic 返回）→ solve → `CellOutput`。
- fingerprint 含 `source` 与 rulesVersion；脚本编辑即过期，沿用手动重跑约定。
- UI：`SCRIPT_EDITOR` 表单控件（solver-api §4）承载源码编辑，v1 纯文本 + 错误行号，
  语法高亮/补全 🚧。
- LLM 路径（ai/roadmap M6）：LLM 产出 `ScriptedRequest.source`，用户在 cell 中审阅
  后运行——代码可见、可改、可保存，天然满足"未来交给 LLM 写代码"的形态。

## 6. 测试约定

- 沙箱：死循环脚本在超时内被中断且 UI 可再次运行；`java.io` 等宿主类不可达。
- 协议往返：builder 产出的 JSON 反序列化为 `ConstraintProgramSpec` 全字段无损。
- 谓词：memo 命中率断言（同 view 单次调用）；脚本异常 → 候选 Fail + 诊断，宿主不崩。
- 金标准：半音下行低音脚本端到端解出并含 line-pattern INDICATION。
- 取消：solve 进行中关闭 cell → 协程与 Context 均释放（无泄漏断言）。

## 7. 开放问题

- `.d.ts` 的生成方式：从 `@Serializable` spec 类型反射生成 vs 手写同步 + 一致性测试；
  倾向后者起步（类型数量可控），S4 接 MCP 时再评估生成器。
- 脚本内 `solve` 配额的具体数值与超额策略（截断返回 vs 报错）。
- Web 端（未来 KMP 目标）：浏览器天然有 JS 引擎，宿主接口需保持可在 JS 平台
  用原生 eval/Worker 实现——`ScriptPredicateHost` 抽象已预留。
