import React from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.tsx";
import { appAssetUrl } from "./paths.js";
import "./styles.css";

const fontStyle = document.createElement("style");
fontStyle.textContent = `@font-face { font-family: "Bravura"; src: url("${appAssetUrl("fonts/Bravura.otf")}") format("opentype"); font-display: block; }`;
document.head.append(fontStyle);

createRoot(document.getElementById("root")).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

if ("serviceWorker" in navigator && import.meta.env.PROD) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register(appAssetUrl("sw.js")).catch(() => {
      // Offline support is progressive; the editor remains usable when registration is blocked.
    });
  });
}
