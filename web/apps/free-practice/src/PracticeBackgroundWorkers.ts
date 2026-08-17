type QueueMessage = Record<string, unknown> & { type: string };

interface BackgroundRequest {
  requestId: number;
  kind: string;
}

interface CoordinatorOptions {
  enqueue: (message: QueueMessage) => void;
  postError: (message: string) => void;
}

const failureTypeByResult: Record<string, string> = {
  backgroundResult: "backgroundFailure",
  teachingCatalogResult: "teachingCatalogFailure",
  findingResult: "findingFailure",
};

/** Owns child search workers and routes every crash back through the shared session. */
export class PracticeBackgroundWorkers {
  private readonly writingWorkers = new Map<string, Worker>();
  private catalogWorker: Worker | null = null;
  private findingWorker: Worker | null = null;
  private pendingCatalogRequestId: number | null = null;
  private pendingFindingRequestId: number | null = null;

  constructor(private readonly options: CoordinatorOptions) {}

  runWriting(request: BackgroundRequest) {
    this.writingWorkers.get(request.kind)?.terminate();
    const worker = this.createWorker();
    this.writingWorkers.set(request.kind, worker);
    const fail = (reason: string) => {
      worker.terminate();
      if (this.writingWorkers.get(request.kind) === worker) this.writingWorkers.delete(request.kind);
      this.options.postError(reason);
      this.options.enqueue({ type: "backgroundFailure", failure: { requestId: request.requestId, reason } });
    };
    worker.onmessage = ({ data }) => {
      if (data.type === "backgroundResult") {
        self.postMessage({ type: "backgroundProgress", requestId: request.requestId });
        this.options.enqueue({ type: "backgroundResult", result: data.result });
      } else if (data.type === "error") fail(data.message ?? "自动写作失败");
    };
    worker.onerror = (event) => fail(event?.message || "自动写作 Worker 已崩溃");
    worker.postMessage({ type: "execute", request });
  }

  runFindings(request: BackgroundRequest) {
    this.pendingFindingRequestId = request.requestId;
    this.findingWorker = this.residentWorker(this.findingWorker, "findingResult",
      () => this.pendingFindingRequestId, () => { this.findingWorker = null; });
    this.findingWorker.postMessage({ type: "executeFindings", request });
  }

  runTeachingCatalog(request: BackgroundRequest) {
    this.pendingCatalogRequestId = request.requestId;
    this.catalogWorker = this.residentWorker(this.catalogWorker, "teachingCatalogResult",
      () => this.pendingCatalogRequestId, () => { this.catalogWorker = null; });
    this.catalogWorker.postMessage({ type: "executeTeachingCatalog", request });
  }

  cancelWriting() {
    for (const worker of this.writingWorkers.values()) worker.terminate();
    this.writingWorkers.clear();
  }

  close() {
    this.cancelWriting();
    this.catalogWorker?.terminate();
    this.findingWorker?.terminate();
    this.catalogWorker = null;
    this.findingWorker = null;
    this.pendingCatalogRequestId = null;
    this.pendingFindingRequestId = null;
  }

  private residentWorker(
    current: Worker | null,
    resultType: string,
    pendingRequestId: () => number | null,
    onDead: () => void,
  ) {
    if (current) return current;
    const worker = this.createWorker();
    const fail = (reason: string, requestId = pendingRequestId()) => {
      this.options.postError(reason);
      if (requestId != null) this.options.enqueue({
        type: failureTypeByResult[resultType], failure: { requestId, reason },
      });
    };
    worker.onmessage = ({ data }) => {
      if (data.type === resultType) this.options.enqueue({ type: resultType, result: data.result });
      else if (data.type === "error") fail(data.message ?? "后台搜索失败", data.requestId ?? pendingRequestId());
    };
    worker.onerror = (event) => {
      onDead();
      worker.terminate();
      fail(event?.message || "后台搜索 Worker 已崩溃");
    };
    return worker;
  }

  private createWorker() {
    return new Worker(new URL("./search-worker.js", import.meta.url), { type: "module" });
  }
}
