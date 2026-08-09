# 构建与开发

面向贡献者的构建、测试与依赖配置说明。整体架构见
[ARCHITECTURE.md](ARCHITECTURE.md)，编码与仓库约束见 [AGENTS.md](../AGENTS.md)。

## 环境

- JDK 17 或更高版本；
- Web 开发使用 Node.js 20 LTS 或更新的受支持版本；
- 使用仓库自带 Gradle Wrapper，不要求贡献者预装固定 Gradle 版本；
- Windows 使用 `./gradlew.bat`，Linux/macOS 使用 `./gradlew`。

## `mecon-components` 依赖

钢琴卷轴键盘由独立的 `mecon-components` 仓库维护。本项目通过
`settings.gradle.kts` 中的 Gradle composite build，从该仓库源码解析
`com.mecon:mecon-components`；项目内不保留键盘组件副本。

该依赖是 Desktop 编译的必要条件。运行 Gradle 前，请先将
`mecon-components` 克隆或检出到本仓库的同级目录 `../mecon-components`：

```text
projects/
├── mecon-components/
└── music-theory-frontend/
```

如果缺少该目录，Gradle 无法解析 `com.mecon:mecon-components`，Desktop 模块将无法编译。

## 常用命令

```powershell
.\gradlew.bat build
.\gradlew.bat test
.\gradlew.bat :apps:desktop:run
.\gradlew.bat :core:jvmTest
.\gradlew.bat :renderer:jvmTest
```

PowerShell 中带点号的 JVM 参数要整体加引号，例如：

```powershell
.\gradlew.bat :apps:desktop:run "-Dmecon.perf=true"
```

## 提交前检查

1. 运行受影响模块的定向测试；
2. 涉及共享模型、编辑协议或 renderer 时，补跑 parity/跨端测试；
3. 检查 `git diff`，确认没有构建输出、个人设置、未经授权的真实作品 XML、密钥或临时文件；
4. 同步相关文档与 [CHANGELOG.md](../CHANGELOG.md)。

大谱性能改动请按 [large-score-editing.md](performance/large-score-editing.md) 验收，编辑能力请按
[score-editing-multiplatform.md](score-editing-multiplatform.md) 检查。Web 的首次安装、Kotlin/JS payload、
Vite、Playwright 与故障排查见 [web-development.md](web-development.md)；自由练习改动按
[free-practice-extension-guide.md](exploration/free-practice-extension-guide.md) 接入。无关 PR 不要修改其
源码、trace 或配套文档。

## 目录约定

- `api`：跨平台数据契约；
- `core`：Runtime/Computed 与音乐规则；
- `renderer`：排版、几何、渲染和命中；
- `apps/desktop`：桌面端 adapter/UI；
- `docs`：当前架构、契约、TODO 与验收门禁。
