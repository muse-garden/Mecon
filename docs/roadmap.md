# 开发路线图

各功能以「已实现 / 部分实现 / 设计阶段 / 未开始」标注当前状态。

## 高优先级（当前迭代）

| 功能 | 状态 | 文档 |
|------|------|------|
| 音符输入（键盘 / MIDI） | 未开始 | — |
| 插件通道增量化（样式 / 注释 / 覆盖层，分析上量前置） | 设计阶段 | [engine-evolution.md](engine-evolution.md) |
| `computeLayout` 窗口局部化 | 未开始（热点已定位） | [engine-evolution.md](engine-evolution.md) |
| 撤销 / 重做 UI 联动 | 部分实现（后端就绪，触发路径缺失） | [state-management.md](state-management.md) |
| 符杠斜率优化（凹凸约束、谱线对齐） | 未开始 | [renderer/stem-and-beam.md](renderer/stem-and-beam.md) |
| 折杠渲染（Kneed Beam） | 部分实现（检测已有，渲染未做） | [renderer/stem-and-beam.md](renderer/stem-and-beam.md) |
| 轨道 Mute / Solo | 部分实现（占位，未实际生效） | [audio/README.md](audio/README.md) |
| 探索模式（notebook 式乐理学习） | 设计阶段 | [exploration/README.md](exploration/README.md) |

## 中优先级

| 功能 | 状态 | 文档 |
|------|------|------|
| 乐理库完整实现（调式、和弦识别） | 部分实现（仅 major/minor + 基础和弦） | [theory/README.md](theory/README.md) |
| 乐理分析与 AI 协作（规则引擎 / MCP / skills） | 设计阶段 | [ai/roadmap.md](ai/roadmap.md) |
| 和弦分析插件 | 部分实现（端到端流通；面板"添加"待 ScoreStateManager 对插件开放） | [plugin/README.md](plugin/README.md) |
| 插件生命周期框架 | 部分实现（注册/安装完成；卸载/热重载未做） | [plugin/plugin-framework.md](plugin/plugin-framework.md) |
| SoundFont 支持 | 未开始 | [audio/README.md](audio/README.md) |
| 多页 / 换行布局 | 已实现（分行分页 + 增量断行 + 逐页缓存） | [renderer/layout.md](renderer/layout.md) |
| 谱表类型扩展（StaffKind：TAB / 打击乐） | 设计阶段 | [engine-evolution.md](engine-evolution.md) |
| splice 契约收敛（新元素类型的增量声明） | 设计阶段 | [engine-evolution.md](engine-evolution.md) |
| 分页 PDF 导出 | 已实现（矢量后端：重放冻结几何，每 surface 一页；字形/文字走 AWT 轮廓填充） | [renderer/pdf-export.md](renderer/pdf-export.md) |

## 低优先级

| 功能 | 状态 | 文档 |
|------|------|------|
| 增量计算 | 已实现（区间扩展方案；DependencyScope 未采用） | [data_model/incremental-update.md](data_model/incremental-update.md) |
| 增量渲染 | 已实现（元素级拼接 / 增量 reflow / 逐页缓存） | [renderer/incremental-rendering.md](renderer/incremental-rendering.md) |
| 跨谱表符杠 | 未开始 | [renderer/stem-and-beam.md](renderer/stem-and-beam.md) |
| Android / iOS 应用 | 设计阶段 | [multiplatform-porting.md](multiplatform-porting.md) §5 |
| Web 前端（TypeScript + React） | 设计阶段 | [multiplatform-porting.md](multiplatform-porting.md) §4 |
| Kotlin/JS 引擎桥接 / 冻结几何 | 设计阶段 | [multiplatform-porting.md](multiplatform-porting.md) §3–4 |
| HarmonyOS NEXT 原生移植 | 可行性门禁 | [multiplatform-porting.md](multiplatform-porting.md) §7 |
| 低延迟实时演奏 | 未开始 | [audio/README.md](audio/README.md) |

## 五线谱记谱扩展候选（按难易）

面向**传统五线谱主线**的记谱元素补全清单（现代记谱 / 六线谱 TAB / 打击乐记谱归
[engine-evolution.md](engine-evolution.md) 的 StaffKind 设计，暂不推进）。难度判据 = 是否已有
「下地」（数据模型或 MusicXML 解析器已存在半成品）。

**已排查到的关键下地**：

- `StorageTextEvent` + `TextType {EXPRESSION, TECHNIQUE, LYRICS, REHEARSAL}` 已在 storage 层定义，
  但 `ComputeEngine` / Renderer / 编辑 UI **完全未消费**（`AnnotationStaffRenderer` 是插件注释谱表，另一回事）——实际是空壳。
- `MusicXmlNoteParser.parseOrnaments` **能解析 trill / mordent / turn / tremolo / wavy-line / glissando**，
  但 `MusicXmlConverter` 未映射进 Storage、**直接丢弃**；歌词走 `skipElement`。导入侧已有一半下地。
- `NoteheadType` 仅 `DOUBLE_WHOLE / WHOLE / HALF / BLACK`（按时值），无形状符头。

### Tier 1 — 易（自成闭环 / 挂到现有管线 / 已有下地）

| 功能 | 状态 | 入口 / 备注 |
|------|------|------|
| 自由文本 / 排练记号 / 表情·技法文字 | 未开始（storage 型已备） | 加 `ComputeEngine.computeTexts` + Renderer `TextElement` + `ScoreElementPalette` 按钮；复用 `EventSection` 选择/拖动/几何 overlay |
| 更多演奏法（上/下弓、开放/闭塞、snap pizz. 等） | 未开始 | 往 `ArticulationLayoutComputer` 堆叠·避线·overlay 加 `Articulation` 枚举 + glyph |
| 符杠斜率优化 + 折杠（kneed beam）渲染 | 部分实现（检测已有） | `BeamLayoutComputer` 内闭环；`BeamGeometry` overlay 就绪。见 [renderer/stem-and-beam.md](renderer/stem-and-beam.md) |
| 形状符头（×·菱形·斜杠 = ghost/harmonics/打点） | 未开始 | 扩 `NoteheadType` + `RenderingProps` 符头类型 + glyph 映射；绘制只动 `NoteheadElement` |

### Tier 2 — 中（新 storage 型 + Computed + Render + palette；多有 MusicXML 下地）

| 功能 | 状态 | 入口 / 备注 |
|------|------|------|
| 装饰记号（trill/mordent/turn/inverted） | 未开始（MusicXML 已解析） | 与 articulation 管线近同构；接上 `MusicXmlConverter` 映射；trill 波线延长挂 `StaffAttachmentLayoutComputer` 区间附件 |
| 琶音 / 滚奏和弦线 | 未开始 | 和弦左侧竖波线，单事件几何、较独立 |
| 滑音 / portamento 线 | 未开始（MusicXML 已解析） | 两音间连线（+波线），与 8va/hairpin 同一条区间附件管线 |
| 踏板记号（Ped.___*） | 未开始 | 谱表下方区间附件，同滑音管线 + 行优先级定义 |
| 指法 / 弦号 / 弓法数字 | 未开始 | 符头附近小文字，需纵向堆叠布局但局部 |
| 震音 tremolo（符干斜杠 / 两音间） | 未开始（MusicXML 已解析） | 会动到符干/符杠几何 |

### Tier 3 — 难（需专用布局算法）

| 功能 | 状态 | 入口 / 备注 |
|------|------|------|
| 歌词（音节对齐） | 未开始（`TextType.LYRICS` 已备） | 需连字符·melisma 延长线·多段纵向堆叠·音节⇄符头对齐专用布局层，可能影响横向间距 |
| 和弦符号（C, Am7, 斜杠和弦） | 未开始 | 谱表上方按拍对齐文字；并入现有和声分析 or 新元素，有设计取舍 |
| 多小节休止（分谱压缩休止 + 数字） | 未开始 | 布局折叠 + 数字，与分行/分页相互作用 |
| 跨谱表符杠完整支持 | 未开始 | `BeamGeometry` 已有跨谱表基准，绘制/编辑未完。见 [renderer/stem-and-beam.md](renderer/stem-and-beam.md) |
| 斜杠记谱 / 小节反复记号（%） | 未开始 | 替代记谱模式，时值解释与绘制均需分支 |
| 提示音符 cue / ossia 谱表 | 未开始 | 缩小谱表·并存布局，谱表隐显机制的扩展 |

**建议着手顺序**：① 文本/排练记号（下地完备、波及最小）→ ② 装饰记号（MusicXML 下地 + articulation 同构）
→ ③ 滑音 / 踏板（直接搭区间附件管线）。三项覆盖「教材谱·古典作品最常缺」的多数要素，且都复用现有增量渲染·几何 overlay·选择/拖动机制。

## 架构演进点

### 增量计算与增量渲染（已落地）

「编辑 → 增量 compute → 增量 layout → 元素级拼接渲染」链路已端到端落地（含撤销/重做 diff、增量 reflow、逐页缓存）。Core 契约见 [data_model/incremental-update.md](data_model/incremental-update.md)，renderer 全景与剩余热点见 [renderer/incremental-rendering.md](renderer/incremental-rendering.md)。下一阶段的规模化方向（插件通道增量化、`computeLayout` 局部化）与记谱扩展（StaffKind / splice 契约）见 [engine-evolution.md](engine-evolution.md)。

### 跨平台 Audio

`ScoreToMidiConverter` / `MidiScore` 作为共享播放计划继续复用；JVM MIDI/FluidSynth、Android、
iOS 与 Web Audio 分别实现 backend。具体顺序见 [multiplatform-porting.md](multiplatform-porting.md)。

### 跨平台 UI

React Web 重写 UI，仅复用 Kotlin/JS 引擎协议；Android/iOS 可共享 Compose 画布、状态和自适应组件，
但手机交互重新设计。平板以输入能力模型组合桌面、触控和笔交互；HarmonyOS NEXT 先通过 H0 PoC。
