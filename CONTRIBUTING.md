# 贡献指南

感谢参与 Mecon。提交代码前请先阅读根目录 [AGENTS.md](AGENTS.md) 与相关模块文档；架构和数据层
变更必须遵守四层边界，乐谱编辑能力必须走共享 `ScoreEditingSession`。

## 开发环境与验证

- JDK 17+；Windows 使用 `.\gradlew.bat`，其他平台使用 `./gradlew`。
- 构建：`.\gradlew.bat build`。
- 全量测试：`.\gradlew.bat test`。
- 只改某模块时，优先运行对应 `:module:jvmTest` / `:module:jvmTest --tests "*TestName"`。
- 文档、脚本和配置改动也要做路径/链接检查；不要把构建输出或本地乐谱放进提交。

## 分支与 Commit

分支使用 `codex/<topic>` 或 `feature/<topic>`；一个 PR 尽量只处理一个主题。

Commit 遵循 Conventional Commits：

```text
<type>(<scope>): <imperative summary>
```

常用 `type`：`feat`、`fix`、`refactor`、`perf`、`test`、`docs`、`build`、`ci`、`chore`。
`scope` 使用模块或领域名，例如 `theory`、`renderer`、`score-editing`。标题使用英文动词开头，
不超过约 72 个字符；正文说明动机、行为变化、兼容性和验证命令。

## Pull Request

PR 描述必须说明：

- 要解决的问题、实现范围和未覆盖范围；
- 涉及的数据模型、跨端协议、renderer splice 或性能契约；
- 测试/手工验证命令及结果；
- 文档、CHANGELOG 和迁移是否同步；
- 关联 issue 或设计文档。

### Vibe coding / AI 辅助提交

若 PR 使用了 vibe coding 或任何生成式 AI 辅助，必须在 PR 描述中附上：

1. 使用的 AI 工具名称及版本/模型（如可知）；
2. 产生关键代码的**原始 prompt**，不得只给总结或改写后的 prompt；
3. AI 生成内容由提交者如何检查、修改和验证；
4. 是否包含第三方代码、外部资料或生成素材及其许可来源。

AI 不能替代提交者对版权、依赖许可、测试和架构约束的责任。PR 模板已提供对应字段。

## 测试素材与进行中的迁移

测试需要乐谱时请在测试代码中构造最小合成 `StorageScore`/`RuntimeScore`，或使用仓库自有 fixture。
不要提交未经确认许可的真实作品 XML、扫描谱或下载文件。针对某个外部样例的回归，应提取可重现的
最小结构，而不是保留原文件。

`free-practice` Web 工作台已形成受跨端 trace 和浏览器 E2E 保护的稳定基线；除非 PR 明确修改该能力，
否则不要顺手重排、重命名或清理其源码、测试数据及配套文档。相关改动按
[自由练习功能扩展指南](docs/exploration/free-practice-extension-guide.md)执行。

## 许可证与 Logo

代码按 [GPLv3-or-later](LICENSE) 发布。新文件应保留合适的版权/许可证说明；引入第三方代码前
必须记录来源和兼容性。根目录 `logo.png` 是项目透明底 Logo，作为仓库主页和发行包的唯一品牌素材
入口；不要复制出多个不同版本。
