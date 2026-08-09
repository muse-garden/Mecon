# Web 乐谱渲染 npm 包

> 状态：✅ 冻结几何轻量包与完整 Kotlin/JS 排版包均已实现。

## 1. 两个包

| 包 | 输入 | 排版位置 | 输出/交互 |
|---|---|---|---|
| `@mecon/frozen-score` | `.mecon` 或 `FrozenScoreBundle` | 不排版，重放已冻结几何 | Canvas、SVG、命中与选择 |
| `@mecon/web-renderer` | `StorageScore` JSON/对象 | 浏览器 Kotlin/JS 完整排版 | 同一 Canvas、SVG、命中与选择 |

源码分别在 `web/packages/frozen-score/` 与 `web/packages/web-renderer/`。完整包依赖轻量包，
因此绘制语义只维护一份。

## 2. 构建

```powershell
cd web
npm ci
npm run prepare:engine
npm test
npm pack --workspace @mecon/frozen-score
npm pack --workspace @mecon/web-renderer
```

`prepareNpmPackage` 将 Kotlin/JS ES module 及其运行时模块同步到完整包的 `kotlin/` 目录。
该目录是构建产物，不提交 Git，但会进入 npm tarball。

## 3. 轻量包

```ts
import { loadMecon, loadMusicFont, renderCanvas, renderSvg, hitTest } from "@mecon/frozen-score";

await loadMusicFont("/fonts/Bravura.otf");
const { bundle } = await loadMecon(await file.arrayBuffer());
renderCanvas(canvas, bundle, { surfaceIndex: 0 });

const selected = hitTest(bundle, 0, x, y);
const svgMarkup = renderSvg(bundle, {
  surfaceIndex: 0,
  selectedIds: selected ? [selected.id] : []
});
```

`.mecon` 读取器使用 manifest 中的 `geometryPath`，不猜测 zip 内路径。它支持 ZIP stored 与
deflate 条目；缺少冻结几何时明确报错，不在轻量包中偷偷执行排版。

命中测试按绘制顺序逆序查询，因此重叠区域返回视觉上最上层的元素。当前 `RenderElement` 已保存
`hitBox`、事件/轨道 ID、measure/system/staff 索引与开放 metadata，足以支持点击、选择、
播放高亮和简单上下文菜单；拖拽编辑仍应由完整引擎根据编辑命令重新排版。

React：

```tsx
import { FrozenScore } from "@mecon/frozen-score/react";

<FrozenScore
  bundle={bundle}
  renderer="svg"
  selectedIds={selection}
  onSelect={(element) => setSelection(element ? [element.id] : [])}
/>;
```

## 4. 完整包

完整包的 Kotlin facade 位于 `bridge/web-engine/`。它只暴露字符串边界：

```text
StorageScore JSON
  → RuntimeScore
  → ComputedScore
  → UnifiedLayoutResult
  → RenderResult
  → FrozenScoreBundle JSON
```

```ts
import { createMeconRendererFromUrls } from "@mecon/web-renderer";

const engine = await createMeconRendererFromUrls({
  metadataUrl: "/bravura/bravuraMetadata.json",
  glyphNamesUrl: "/bravura/glyphnames.json"
});

const bundle = engine.renderCanvas(canvas, storageScore);
const { svg } = engine.renderSvg(storageScore);
```

React 使用 `@mecon/web-renderer/react` 的 `MeconScore`。大乐谱应在 Web Worker 中调用
`engine.layout(score)`，主线程只消费返回的冻结 bundle。

JS 的普通文字测量使用确定性字体宽度近似，不读取 DOM，保证 Worker 可运行。音乐字形尺寸仍来自
SMuFL metadata。需要桌面与 Web 普通文字逐像素一致时，应改为显式、可注入且带字体指纹的度量表。

### 编辑壳层边界

`@mecon/web-renderer` 暴露的 score editor 只是到共享 `ScoreEditingSession` 的字符串 facade。
React 侧负责命中、平台输入、瞬时 preview 和发送普通 JSON intent；不得直接修改
`StorageScore`、维护另一套历史或复制音乐规则。全谱编辑与排版在 Worker 串行执行，主线程只消费
不可变更新帧。连续 surface 的浏览器指针坐标是画布局部坐标，而冻结元素 hit box 保持 renderer
全局坐标，区域命中须补回 `bundle.bounds.origin`；分页 surface 的元素已是页内坐标，不得再叠加
`contentOffsetY`。新增编辑能力的完整接入顺序见
[乐谱编辑多端接入规范](../score-editing-multiplatform.md)。

完整、可嵌入的 React 编辑 UI 已由可选的 `@mecon/web-renderer/editor` 与
`@mecon/web-renderer/editor/react` 子路径提供；主入口继续保持 headless。`ScoreEditor`、
`useScoreEditorController`、完整/自由练习 toolbar profile、hidden controls、host slots 与自定义区域
布局的使用和扩展规则见[自由练习功能扩展指南](../exploration/free-practice-extension-guide.md)；构建与运行见
[Web 开发说明](../web-development.md)。

## 5. 新元素与兼容

新增音乐元素通常只会新增 `RenderElementType`，并继续组合既有 `RenderCommand`：

1. Computed 层决定是否生成元素；
2. Renderer 生成既有命令；
3. 轻量包不升级也可绘制；
4. 新 `element.type` 仍可通过 hit box 选择。

真正新增绘图原语时：

1. 为 Kotlin `RenderCommand` 增加可序列化子类；
2. 同时在轻量包的 Canvas 与 SVG dispatch 中实现；
3. 增加 wire fixture 测试；
4. 不兼容字段变化才提升 `FrozenScoreBundle.SCHEMA_VERSION`。

轻量包读取未知字段；未知元素类型透明保留。未知命令会跳过，并调用 `onUnknownCommand`，因此旧包
不会整份崩溃，但发布新命令时仍应同步提升两个 npm 包的次版本。
