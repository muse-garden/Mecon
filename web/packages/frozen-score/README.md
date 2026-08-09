# @mecon/frozen-score

Zero-layout browser renderer for the `geometry/<scoreId>.json` entry in a `.mecon`
container. It supports Canvas2D, SVG, hit testing, selected-element overlays, and an
optional React component.

```js
import { loadMecon, loadMusicFont, renderCanvas } from "@mecon/frozen-score";

await loadMusicFont("/fonts/Bravura.otf");
const { bundle } = await loadMecon(await file.arrayBuffer());
renderCanvas(document.querySelector("canvas"), bundle);
```

Unknown element types remain selectable and drawable because drawing dispatch is based on
the command type. Unknown command types are skipped and reported through
`onUnknownCommand`, allowing an older viewer to display all primitives it understands.
