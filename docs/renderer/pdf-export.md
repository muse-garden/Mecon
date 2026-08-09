# PDF 导出（矢量）

把当前乐谱导出为矢量 PDF，**每个 surface 一页**。实现完全建立在**冻结几何**之上：先把
`StorageScore` 经引擎排版成 `FrozenScoreBundle`，再逐页重放其中的 `RenderCommand`。与屏幕
所见一致（同一套命令、同一份 Bravura），只是换了个绘制后端。

## 入口

- File 工具栏「导出」按钮 → 下拉菜单（`FileActions.ExportMenuButton`，用统一的
  `MeconDropdownMenu` / `MeconDropdownItem` 组件，背景 / 边框 / 文字 / 图标全取自 `MeconColors`
  以支持换肤），可选 **PDF** / **MusicXML**。
- PDF → `ScoreFileController.exportPdf()`；MusicXML → `exportMusicXml()`。两者共用
  `runExport(...)`：选目标 → 后台写文件 → 状态横幅（`apps/desktop/.../service/ScoreFileController.kt`）。
- 弹保存对话框（PDF 用 `showExportPdfDialog`，MusicXML 用 `showExportMusicXmlDialog`），默认名取
  当前文档名换对应扩展名。
- 导出用 `ScoreSession.storageScoreForSave`，因此会带上最新的连音线 / 演奏法**手动几何**，
  和保存 `.mecon` 时落库的一致。

### 导出提示

`runExport` 期间置 `exporting=true`（下拉按钮禁用），横幅先显示「正在导出 <格式>…」，成功后转
「已导出 <格式>：<文件名>」并在 4s 后自动消失；失败改走红色 `loadError` 栏。横幅由
`App.kt` 读取 `ScoreFileController.exportMessage` 渲染，点击可手动关闭。

## 管线

```
StorageScore
  └─ FrozenScoreRenderer.render(score, font)   // 与 .mecon 打包共用同一排版入口
        └─ FrozenScoreBundle(surfaces=[FrozenSurface…])
              └─ ScorePdfExporter.writePdf(bundle, staffSpaceMm, bravuraOtf, file)
                    └─ 每 surface 一页：PdfContentWriter 重放 element.commands
```

- `FrozenScoreRenderer`（`apps/desktop/.../service/FrozenScoreRenderer.kt`）是**唯一**的
  “乐谱 → 冻结几何” 缝：`.mecon` 容器打包（`MeconDocumentService`）与 PDF 导出都走它，
  绝不各写一套排版参数。
- `ScorePdfExporter`（`apps/desktop/.../export/ScorePdfExporter.kt`）负责 PDF 文档 / 分页 /
  坐标换算；`writePdf(...)` 是纯函数（不加载字体、不跑引擎），便于单测。

## 后端选型

Skiko（随 Compose 打包的 Skia）**不含 PDF 后端**（只有 SVG），所以用
`org.apache.pdfbox:pdfbox`（Apache-2.0，JVM only，仅 desktop 模块）写 PDF 容器与内容流的
矢量绘制算子。

**字形与文字一律转成矢量轮廓填充**（`AwtGlyphOutliner` 用 `java.awt.Font` +
`GlyphVector.getOutline`），而非 PDF 文字算子：

- 免去字体内嵌 / 子集化 / CMap 的全部工程量与坑。
- 任意 JVM 能塑形的字符都能画——**包括 CJK 标题**。
- 音乐字形用随包 `fonts/Bravura.otf`（与屏幕 Skia 读的是同一文件，轮廓一致）；普通文字用
  逻辑字体 `Serif` / `SansSerif`（映射到覆盖面广的系统字体）。
- 代价：PDF 里文字不可选中 / 检索（是轮廓，不是文本）。

## 坐标换算

引擎在**设计像素**空间排版（`ScaleFactor.DEFAULT` = 8 px / staff space，y 向下）。一个
staff space 对应 `PageLayoutConfig.staffSpaceMm` 毫米纸面，于是有固定的

```
ptPerPixel = (staffSpaceMm / 8) * (72 / 25.4)
```

每页设一个 CTM `Matrix(ptPerPixel, 0, 0, -ptPerPixel, 0, pageHeightPt)`：既缩放到纸面点，
又把 y 翻到 PDF 的 y-up。因此 A4 分页 surface 出来正好是真 A4 尺寸；线宽 / 虚线间隔以像素
传入，随 CTM 一并缩放。

- 超过 PDF 单页上限（14400 pt，约 200 in，如未分页的长连续谱）时，整册统一降比以塞进上限。
- `EDITOR_MARKER` 元素（光标 / 选区浮层）是编辑期临时物，导出时跳过。

## 命令映射（`PdfContentWriter`）

| RenderCommand | PDF |
|---|---|
| `DrawLine` | `moveTo/lineTo` + `stroke`（线帽、虚线 `setLineDashPattern`）|
| `DrawRect` | `addRect` + fill/stroke |
| `DrawEllipse` | 四段三次贝塞尔逼近 + fill/stroke |
| `DrawPath` | 逐段 `moveTo/lineTo/curveTo`；二次段升三次；`closePath` |
| `DrawBezier` | 单段 `curveTo` + `stroke`（filled→midpoint 粗细圆帽，否则 endpoint 粗细）|
| `DrawGlyph` | Bravura 轮廓 → path fill（`scaleX/scaleY` 绕锚点缩放）|
| `DrawText` | 文字轮廓 → path fill（对齐、richText 逐 run 大小/粗斜/上下标）|
| `RenderGroup` | 递归展开（opacity/clip 暂扁平化）|

每条命令包在 `q`/`Q`（save/restore）里，颜色 / alpha / 线宽不外泄；alpha<255 用
`PDExtendedGraphicsState` 设透明度；填充遵循轮廓的缠绕规则（非零 / 奇偶）。

## 测试

`apps/desktop/.../export/ScorePdfExporterTest.kt`：手搓 `FrozenScoreBundle`（含线、矩形、
音符字形、Latin+CJK 文字、连线，及一个应被丢弃的 `EDITOR_MARKER`）→ `writePdf` → 用 PDFBox
重新载入校验页数与页面物理尺寸；并验空 bundle 也产出合法单页 PDF。绕开排版引擎，专测后端。

## 未做 / 后续

- 打印（走系统打印）🚧。
- 可选中文字（改真·内嵌字体后端）🚧——当前为轮廓，优先保证视觉与 CJK 保真。
- 导出范围选择（页码区间 / 单页）🚧——当前导出全部 surface。
