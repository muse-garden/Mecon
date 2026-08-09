# `.mecon` 容器格式

> 基线：2026-07-27。取代旧的「单一 YAML `.mecon`」为一个 **zip 容器**：一个文件承载多张乐谱、
> 可插拔模块（exploration / analysis / improvisation …）、工作区偏好，以及供网页轻量展示器直接
> 回放的**冻结几何**。旧的单乐谱 YAML 样本已改名为 `*.mscore.yaml`（见 `test-scores/`）。

## 1. 为什么是容器

单一 YAML 只能存一张乐谱，且不含渲染结果——网页端要展示就必须载入完整排版引擎。新格式解决三件事：

1. **一文件多内容**：多张乐谱 + 跨乐谱/工作区级模块，未来新增模块类型零改容器。
2. **版本与偏好**：`manifest.json` 记录格式版本、引擎版本、时间戳、活动乐谱与工作区偏好。
3. **冻结几何**：每张乐谱附一份 `FrozenScoreBundle`（平台无关的 `RenderCommand` + 命中元数据），
   网页 Canvas2D 只回放命令，**无需 Compute / Layout / Render**（见
   [multiplatform-porting.md](../multiplatform-porting.md) §4.1）。

## 2. 归档布局

`.mecon` 是一个 zip，条目路径固定、由 `manifest.json` 索引（读者先解析 manifest，再按其记录的
路径取条目，不臆测布局）：

```
bundle.mecon (zip)
├── manifest.json            # MeconManifest：版本 / 时间戳 / 乐谱与模块引用 / 工作区偏好
├── scores/<scoreId>.json    # StorageScore（复用 ScoreSerializer 的共享 JSON codec）
├── geometry/<scoreId>.json  # FrozenScoreBundle（可选；网页展示器读取，桌面重渲染时忽略）
└── modules/<moduleId>.json  # MeconModuleEntry：不透明 JsonElement 载荷，模块族自有 schema
```

序列化统一用 **JSON**（多端一致，避免 Kaml 的 JS/Native 实验状态，见移植文档 §2.3 第 7 条）。

## 3. 分层归属

严格沿用四层与模块依赖方向（`renderer → core → api`）：

| 部分 | 位置 | 说明 |
|------|------|------|
| Manifest / 模块信封 / 文档模型 / 文本 codec | `core/.../container/` | 不依赖 renderer；只处理 manifest+乐谱+模块 |
| `FrozenScoreBundle` / 投影 / codec | `renderer/.../frozen/` | 从 `RenderResult` 投影；持有 `RenderCommand` 多态层 |
| zip I/O + 渲染几何 + 编排 | `apps/desktop/.../service/` | 平台边（`java.util.zip`）；保存时跑引擎生成几何 |

**几何为何不进 core 文档模型**：`FrozenScoreBundle` 是 renderer 类型。若让 `MeconDocument` 直接持
有它，core 就得依赖 renderer。因此几何作为**独立条目**由桌面 packager 编码后加入归档，网页展示器
也只读 `manifest + geometry` 两类条目，天然绕开文档模型与排版引擎。

## 4. 关键类型

- **`MeconManifest`**（`core`）：`formatVersion` / `engineVersion` / `createdAt` / `modifiedAt` /
  `scores: List<MeconScoreRef>` / `modules: List<MeconModuleRef>` / `activeScoreId` / `workspace`。
  `MeconScoreRef` 携带 `id`、`title`、`path`、可选 `geometryPath`。
- **`MeconModuleEntry`**（`core`）：`id` / `type` / `schemaVersion` / 可选 `scoreId` /
  `payload: JsonElement`。**载荷不透明**——每个模块族（`exploration` / `analysis` /
  `improvisation` / 未来类型）自有并自行演进 schema，容器永不因新增模块而改动；不认识的 `type`
  也能原样 round-trip，不被丢弃。
- **自由练习模块**（`exploration`）：使用 `type = "exploration.free-practice"`。v5 payload
  保存复音/双谱表设置、初始调性以及 `HarmonyWorkspaceState`；工作区包含和声槽、可重叠调性布局、
  逐槽调性读法、枢纽标记与章节来源的惯用进行实例。`scoreId` 指向承载实际音符/休止/连线的
  score entry。v6 🚧 增加自动写作/回放偏好；候选、最后写作范围、finding、选区和撤销栈均不
  持久化。v1–v4 由模块族 codec 与关联 score 的 snapshot migrator 协同迁移。
- **`MeconDocument`**（`core`）：内存态 = `manifest + scores + modules`。刻意**非 `@Serializable`**
  （容器是多条目 zip，几何在平台边处理）。`MeconBundleCodec.writeTextEntries` / `readDocument`
  负责文本条目的组装与解析。
- **`FrozenScoreBundle`**（`renderer`）：`schemaVersion` / `engineVersion` / `fontFingerprint` /
  `paginated` / `bounds` / `surfaces: List<FrozenSurface>` / `timePositions`。`FrozenSurface` 直接
  持有已 `@Serializable` 的 `RenderElement` 列表（含 `RenderCommand` + 事件/小节/系统/谱表元数据与
  命中框）；连续谱一个 surface，分页谱一页一个。`FrozenScoreProjector` 从 `RenderResult` 投影并把
  瞬态选中/高亮归零。`fontFingerprint` 记录 Bravura 精确身份——网页须加载同一字体方可宣称像素一致。

## 5. 桌面读写流程

- **打开**：`.mecon` → `MeconArchive.read` → `MeconBundleCodec.readDocument` →
  安装 `MeconDocument.activeScore` 到 `ScoreSession`，并把整份文档暂存于
  `ScoreSession.loadedContainer` 作为**侧车**。若 `workspace.activeModuleId` 指向
  `exploration.free-practice`，桌面同时恢复其 payload 与关联乐谱，并自动进入探索页的自由练习
  工作台；未知模块仍只作为侧车保留。
- **保存**：`MeconDocumentService.buildDocument(active, base = loadedContainer, now)` 用当前编辑后的
  乐谱替换侧车中的同 id 乐谱，**保留**其余乐谱、模块与工作区偏好；随后 `save` 对每张乐谱跑
  `RenderEngine.render` → `FrozenScoreProjector` 生成 `geometry/<id>.json`，与文本条目一并写入 zip。
  字体不可用时仍写文件，仅跳过几何（保存不因几何失败而失败）。
  自由练习工作台保存时在同一构建事务中替换关联 score 与 module，并把
  `workspace.activeModuleId`、`activeScoreId` 分别指向二者。

> 侧车机制确保「打开多乐谱/带模块的 `.mecon` → 只编辑其一 → 保存」不会把文件塌缩成单乐谱。

## 6. 兼容与迁移

- **旧单乐谱 YAML** 继续可读：桌面按扩展名分派——`.mecon` 走容器，`.mscore.yaml` / `.yaml` / `.yml`
  走 `ScoreSerializer.fromYaml`，`.xml` / `.musicxml` 走 MusicXML。默认保存格式为 `.mecon` 容器。
- **测试夹具**：`test-scores/*.mscore.yaml`（原 `*.mecon`）仍是单乐谱 YAML，供快照与加载测试使用；
  快照几何金标准在 `test-scores/snapshots/`（基名去除 `.mscore.yaml`）。
- **版本演进**：不兼容布局改动才升 `MeconFormat.FORMAT_VERSION`；几何独立升
  `FrozenScoreBundle.SCHEMA_VERSION`。两侧 codec 均 `ignoreUnknownKeys`，旧读者可读新文件的已知字段。

## 7. 测试

- `core`：`MeconContainerCodecTest`——多乐谱 + 模块 round-trip、未知模块类型原样保留、缺 manifest /
  悬挂引用报错、`activeScoreId` 缺省取首张。
- `renderer`：`FrozenScoreProjectorTest`——真实渲染 → 投影 → JSON round-trip；连续/分页 surface 数。
- `apps/desktop`：`MeconDocumentServiceTest`——zip 字节 round-trip、`buildDocument` 侧车保留、
  自由练习 module/score 原子替换、无字体保存后仍可读、多乐谱容器端到端。
