import { createMeconFreePracticeExecutor } from "@mecon/web-renderer";

let executorPromise = createMeconFreePracticeExecutor();

self.onmessage = async ({ data }) => {
  if (data.type !== "execute" && data.type !== "executeTeachingCatalog" && data.type !== "executeFindings") return;
  try {
    const executor = await executorPromise;
    self.postMessage(data.type === "execute" ? {
      type: "backgroundResult",
      result: executor.execute(data.request),
    } : data.type === "executeTeachingCatalog" ? {
      type: "teachingCatalogResult",
      result: executor.executeTeachingCatalogRequest(data.request),
    } : {
      type: "findingResult",
      result: executor.executeFindingRequest(data.request),
    });
  } catch (error) {
    self.postMessage({ type: "error", message: error?.message ?? String(error) });
  }
};
