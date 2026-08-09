# @mecon/web-renderer

The complete Kotlin/JS Mecon engraving engine plus the same Canvas2D/SVG viewer used by
`@mecon/frozen-score`.

Build the engine payload before packing:

```powershell
cd web
npm run prepare:engine
```

```js
import { createMeconRendererFromUrls } from "@mecon/web-renderer";

const engine = await createMeconRendererFromUrls({
  metadataUrl: "/bravura/bravuraMetadata.json",
  glyphNamesUrl: "/bravura/glyphnames.json"
});

const bundle = engine.renderCanvas(document.querySelector("canvas"), storageScore);
```

The facade accepts and returns JSON-compatible values only. Run it in a Web Worker for large
scores; transfer the returned frozen bundle to the main thread for Canvas/SVG replay and hit
testing.

The optional `@mecon/web-renderer/editor` and `@mecon/web-renderer/editor/react` exports provide
the shared interaction helpers, toolbar profiles, `ScoreEditor`, and `useScoreEditorController`.
Application shells must still dispatch plain intents to the shared Kotlin session; the React
component does not own score business rules or history.

Repository build/run instructions live in
[`docs/web-development.md`](../../../docs/web-development.md); free-practice extension rules live in
[`docs/exploration/free-practice-extension-guide.md`](../../../docs/exploration/free-practice-extension-guide.md).
