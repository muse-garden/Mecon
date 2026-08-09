# 图片 / PDF 乐谱识别（OMR）接入设计

> **状态**：🚧 设计阶段  
> **调研日期**：2026-07-23  
> **目标平台**：Compose for Desktop

## 1. 结论

首版采用 **Audiveris 5.11 独立进程 + MusicXML 交换**：

```text
图片 / PDF
  → Audiveris CLI（独立进程）
  → .mxl（压缩 MusicXML）
  → MxlReader
  → MusicXmlConverter.import()
  → StorageScore
  → Runtime / Computed / Renderer
```

选择理由：

- 它是目前开源方案中最完整的可用产品：支持印刷体通用西方记谱、多页 PDF 与
  TIFF/JPG/PNG/BMP，提供批处理 CLI、MusicXML 4.0 输出和人工校正界面。
- Mecon 已有完整的 MusicXML → `StorageScore` 管线；把 OMR 限定在文件边界可避免复制记谱语义。
- 独立进程隔离长耗时、崩溃、原生依赖和 AGPL 代码，且以后可替换为其他后端。

首版不承诺手写谱、简谱、吉他谱、古谱或“零错误识别”。OMR 输出必须视为待校对草稿。

## 2. 开源方案调研

| 方案 | 输入 / 输出 | 许可证 | 成熟度与限制 | 决策 |
|------|-------------|--------|--------------|------|
| [Audiveris 5.11](https://github.com/Audiveris/audiveris/releases) | 多页 PDF、TIFF、JPG、PNG、BMP → `.mxl` | AGPL-3.0 | 活跃；完整页面管线、CLI、人工编辑；只支持印刷体 CWMN，精度仍需人工复核 | **P0 默认后端** |
| [homr](https://github.com/liebharc/homr) | 单张图片 → MusicXML | AGPL-3.0 | 活跃；UNet + Transformer，对手机照片友好；当前重点是高/低音谱号的音高和节奏，力度、演奏法、重升降等仍有限；多图需另行合并 | P2 实验后端 |
| [oemer](https://github.com/BreezeWhite/oemer) | 单张图片 → MusicXML | MIT | ONNX/TensorFlow，可处理倾斜照片；单张 GPU 仍可能需 3–5 分钟，PyPI 最新版为 2024-11，项目自身推荐 homr | 宽松许可证备选 |
| [Clarity-OMR](https://github.com/clquwu/Clarity-OMR) | 多页 PDF → MusicXML | GPL-3.0 | 2026 年新项目，YOLO + Transformer，直接提供 300 DPI PDF 管线；仅少量提交且无正式 release | 观察，不进 P0 |
| [SMT / SMT++](https://github.com/antoniorv6/SMT) | 全页钢琴谱图片 → **kern 等序列 | MIT | 有论文与权重，适合研究/训练；目标记谱域较窄，缺少产品级 PDF、校正和 MusicXML 组装管线 | 研究储备 |
| [Transcoda](https://arxiv.org/abs/2605.10835) | 图片 → **kern | 模型/代码需逐项复核 | 2026 年研究模型，语法约束解码有价值；不是可直接替换的页面级 MusicXML 产品 | 研究储备 |

homr 的自有 benchmark 显示其在多个数据集上有竞争力，但作者也明确说明解析器可能偏向自身，
且与其他论文指标并非完全可比。因此选型不能只看公开数字，必须用 Mecon 自建样本复测。

### 2.1 为什么不直接嵌入模型

- Audiveris 是 Java，但其内部 API 不是稳定 SDK；直接依赖会把 AGPL、UI/原生库和升级风险带入主进程。
- Python 模型需要额外运行时、权重、CUDA/ONNX 组合，桌面分发和故障面明显更大。
- 所有候选都能落到 MusicXML 或可转换的符号格式；MusicXML 是当前成本最低的稳定边界。

## 3. 范围

### P0

- 从菜单选择 PDF、PNG、JPG/JPEG、TIFF/TIF、BMP。
- 探测用户已安装的 Audiveris，允许在设置中指定可执行文件。
- 后台识别、进度/日志、取消、超时和失败恢复。
- 读取 Audiveris 生成的一个或多个 `.mxl`，展示 movement 列表。
- 导入前做 MusicXML 兼容性扫描与结构校验，用户确认后替换当前文档。
- 保留原始输入和 `.omr` 的定位信息，便于跳转 Audiveris 人工修订。

### 非目标

- 首版不在 Mecon 内实现逐符号图像覆盖校正。
- 不自动把低置信度结果静默修成“看似合理”的音乐。
- 不上传云端；远程 OMR 后端须另做隐私和鉴权设计。
- 不让 OMR 直接构造 Runtime/Computed/Render Geometry。

## 4. 架构

### 4.1 组件

```text
FileActions / ImportOmrDialog
        │
        ▼
OmrImportController ── StateFlow<OmrJobState>
        │
        ▼
OmrService
  ├── OmrBackendRegistry
  ├── AudiverisCliBackend
  ├── OmrWorkspaceManager
  └── OmrResultValidator
        │
        ├── Process（不经 shell）
        └── MxlReader → MusicXmlConverter
```

建议首版全部放在 `apps/desktop` 的 `service/omr/`；只有出现第二个正式后端后，才抽取 JVM
模块 `omr-api`。不要提前把 `File`、进程或模型类型加入 KMP `api`。

### 4.2 后端契约

```kotlin
interface OmrBackend {
    val id: String
    suspend fun probe(): OmrBackendStatus
    suspend fun recognize(
        request: OmrRequest,
        onProgress: (OmrProgress) -> Unit
    ): OmrBackendResult
    suspend fun cancel(jobId: OmrJobId)
}

data class OmrRequest(
    val jobId: OmrJobId,
    val input: Path,
    val workspace: Path,
    val pageSelection: IntRange? = null
)

data class OmrBackendResult(
    val artifacts: List<OmrArtifact>,
    val logFile: Path,
    val backendVersion: String
)
```

`OmrArtifact` 记录 movement 名称、`.mxl` 路径、可选 `.omr` 路径和页范围。
这些是导入会话数据，不写入 `StorageScore`，避免污染乐谱四层模型。

### 4.3 Audiveris 调用

使用参数数组启动，不拼接 shell 字符串：

```text
audiveris -batch -transcribe -export -save -swap
  -output <job-output> -- <input>
```

- `-batch`：无 GUI；`-transcribe -export`：识别并导出 MusicXML。
- `-save`：保留 `.omr`，失败后可用 Audiveris GUI 修订；`-swap` 降低长 PDF 内存占用。
- 输出目录必须是每个 job 独占的临时目录；不根据 stdout 猜文件名，而是在进程成功后枚举
  白名单扩展名并校验修改时间与归属。
- Audiveris 可能按 movement 生成多个 `.mxl`，UI 必须让用户选择，不能默认只取第一个。

### 4.4 `.mxl` 前置支持

当前 `MusicXmlConverter` 只接受 XML 字符串，而 Audiveris 默认输出 `.mxl`。P0 先在 `core`
增加 `MxlReader` 与字节入口：

```kotlin
fun importBytes(bytes: ByteArray, fileName: String): Result<StorageScore>
```

`.mxl` 按 MusicXML 4.0 规范处理：

1. 验证 ZIP 头、总展开大小、条目数、单条目大小和压缩比。
2. 只读取 `META-INF/container.xml`，拒绝 DTD/外部实体。
3. 解析 `rootfile full-path`，规范化后确保仍在归档根内。
4. 只把声明的 MusicXML rootfile 读入内存，不把整个 ZIP 解压到磁盘。
5. 限制 XML 大小并交给现有 `MusicXmlConverter.import()`。

完成后把 `.mxl` 纳入 `ScoreFileService.MUSICXML_EXTENSIONS`，使普通 MusicXML 打开能力也受益。

## 5. 用户流程

1. `文件 → 从图片/PDF 识别…`，独立于普通“打开”。
2. 首次使用探测 Audiveris；缺失时显示官方安装页和“选择可执行文件”，不自动执行下载。
3. 选择文件后显示页数、文件大小、后端、隐私提示和“仅支持印刷谱”说明。
4. 识别页展示阶段、当前页/总页数（若日志可解析）、耗时、最近日志与取消按钮。
5. 完成后列出 movements、解析警告和结构校验结果，可试听/预览识别谱。
6. 用户点击“导入”后才调用 `ScoreSession.replaceDocument()`；取消或失败不改变当前文档。
7. 若结果较差，提供“在 Audiveris 中校正 `.omr`”和“打开日志目录”。

导入前沿用现有未保存文档确认流程；当前工程尚无统一 dirty-state 对话框时，将其列为本功能前置，
不能让长时间识别结束后无提示覆盖编辑内容。

## 6. 状态、线程与取消

```kotlin
sealed interface OmrJobState {
    data object Idle : OmrJobState
    data class Running(val phase: OmrPhase, val progress: Float?) : OmrJobState
    data class Reviewing(val candidates: List<OmrCandidate>) : OmrJobState
    data class Failed(val error: OmrError, val logFile: Path?) : OmrJobState
}
```

- 文件探测、进程等待、日志读取：`Dispatchers.IO`。
- `.mxl` 解包、MusicXML 解析与结构校验：`Dispatchers.Default`。
- Runtime/Computed 构建继续走现有 `ScoreSession.replaceDocument()` 路径。
- 同时只运行一个本地 OMR job；取消时先正常终止，再在短宽限期后终止进程树。
- UI 协程取消必须传播到子进程；应用退出时清理所有活跃 job。
- 进度日志格式不是稳定 API：解析失败时显示不确定进度和阶段文本，不把它当作成功判据。

## 7. 校验与诊断

MusicXML 能导入不等于识别正确。`OmrResultValidator` 至少产生：

- 致命：归档/根文件非法、非 partwise、无 part、无有效小节、解析失败。
- 警告：声部小节时值与拍号不符、同声部事件重叠、谱表数突变、空页/空系统、
  不支持的歌词/和弦符号/数字低音、异常多的隐式休止或临时记号。
- 信息：Mecon MusicXML 当前的损失项，如文本、跨谱表、volta 等。

结果采用 `MusicXmlImportResult(score, diagnostics)` 新入口，保留现有
`MusicXmlConverter.import(): Result<StorageScore>` 兼容调用。诊断只提示，不擅自改写音高或节奏。

## 8. 安全、隐私与许可证

- 默认全本地处理；不收集源文件、识别结果或日志。
- 输入做扩展名、magic bytes、文件大小和页数双重校验；路径通过 `ProcessBuilder(List<String>)`
  传入，禁止 shell 插值。
- job 目录位于应用缓存的固定子目录；启动和定期清理过期目录，清理前校验绝对路径仍在该根目录。
- ZIP/XML 采用防 zip bomb、Zip Slip、XXE 的限制；日志展示前过滤控制字符。
- Audiveris 与 homr 为 AGPL-3.0。P0 仅连接用户安装的独立程序，并记录版本；是否随 Mecon
  安装包捆绑、自动下载或托管为服务，必须先做许可证评审。若未来分发其二进制，应同时履行
  对应许可证、源码提供和声明义务。Mecon 仓库当前未发现顶层许可证文件，此问题不能靠进程隔离替代。

## 9. 测试与验收

### 自动测试

- `MxlReaderTest`：标准容器、多 rootfile、缺 container、路径穿越、XXE、zip bomb 限制。
- `AudiverisCliBackendTest`：空格/中文路径、超时、取消、非零退出、多 movement、残缺输出。
- `OmrResultValidatorTest`：拍号/时值、空谱、未知元素与诊断稳定性。
- `OmrImportControllerTest`：取消不换文档、失败不换文档、确认后只安装所选 movement。

### 固定样本

在 `test-scores/omr/` 建立不提交版权受限原件的清单；可提交样本必须是公版或自制，覆盖：

- 干净扫描、手机透视/阴影、低分辨率、双谱表钢琴、多声部、管弦多谱表、歌词与复杂连音。
- 每份保存 ground-truth MusicXML、后端版本、运行环境和预期诊断。
- 指标至少包含音高 F1、onset/duration F1、小节完整率、MusicXML 导入成功率、人工修订时间。

P0 验收门槛以“稳定完成和可校正”为主：全部合法样本不崩溃、不覆盖当前文档、取消可回收进程、
所有输出均可被 Mecon 导入。识别准确率门槛须在首轮基准完成后按谱种分层设定，不能引用不同
数据集上的论文数字直接承诺。

## 10. 实施顺序

1. **P0a**：`MxlReader`、`.mxl` 普通打开、兼容性诊断与安全测试。
2. **P0b**：`OmrBackend`、Audiveris 探测/CLI/取消、临时目录和日志。
3. **P0c**：导入向导、多 movement 评审、预览、文档替换与 i18n。
4. **P1**：自建 OMR 基准、输入质量提示、跳转 Audiveris 校正。
5. **P2**：在基准证明收益后接 homr；再评估原生图像覆盖校正与置信度 source map。

预计涉及：

- `core/.../musicxml/`：`.mxl` 与带诊断的导入入口。
- `apps/desktop/.../service/omr/`：后端、job、进程和校验编排。
- `apps/desktop/.../ui/dialogs/`：文件过滤和 OMR 导入向导。
- `ScoreFileController.kt` / `FileActions.kt`：入口与最终文档安装。
- `BuiltinStrings.kt`：中英文状态、警告和错误。

## 11. 参考资料

- [Audiveris Handbook：能力、限制与人工校正](https://audiveris.github.io/audiveris/_pages/handbook/)
- [Audiveris CLI 参数](https://audiveris.github.io/audiveris/_pages/guides/advanced/cli/)
- [Audiveris 输入格式](https://audiveris.github.io/audiveris/_pages/tutorials/quick/load/)
- [Audiveris 5.11 release](https://github.com/Audiveris/audiveris/releases/tag/5.11.0)
- [homr README 与模型管线](https://github.com/liebharc/homr)
- [oemer README](https://github.com/BreezeWhite/oemer)
- [SMT++ 论文与实现](https://github.com/antoniorv6/SMT-plusplus)
- [MusicXML 4.0 压缩容器规范](https://www.w3.org/2021/06/musicxml40/container-reference/elements/container/)

