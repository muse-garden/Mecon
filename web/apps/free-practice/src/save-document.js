const MECON_TYPE = "application/vnd.mecon+zip";

export async function saveMeconDocument(bytes, {
  showSaveFilePicker = typeof globalThis.showSaveFilePicker === "function"
    ? globalThis.showSaveFilePicker.bind(globalThis)
    : null,
  triggerDownload = downloadFallback,
} = {}) {
  if (typeof showSaveFilePicker !== "function") {
    triggerDownload(bytes);
    return "downloaded";
  }

  try {
    // Call the picker before awaited preprocessing so transient user activation is still valid.
    const handle = await showSaveFilePicker({
      suggestedName: "free-practice.mecon",
      types: [{
        description: "Mecon 文档",
        accept: { [MECON_TYPE]: [".mecon"] },
      }],
    });
    const writable = await handle.createWritable();
    await writable.write(new Blob([bytes], { type: MECON_TYPE }));
    await writable.close();
    return "saved";
  } catch (error) {
    if (error?.name === "AbortError") return "cancelled";
    throw error;
  }
}

function downloadFallback(bytes) {
  const url = URL.createObjectURL(new Blob([bytes], { type: MECON_TYPE }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "free-practice.mecon";
  anchor.click();
  URL.revokeObjectURL(url);
}
