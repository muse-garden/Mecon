import React from "react";
import { ChevronDown } from "lucide-react";
import { practiceFindingText } from "./practice-finding-text.js";

function PracticeFindingItem({ finding, index, onFocusFinding }) {
  const text = practiceFindingText(finding);
  return <li key={`${finding.messageKey}-${finding.ruleId ?? index}`} data-severity={finding.severity}>
    <strong>{text.title}</strong>
    {!!text.detail && <small>{text.detail}</small>}
    {!!finding.anchors?.length && <button onClick={() => onFocusFinding(finding)}>在乐谱中定位</button>}
  </li>;
}

export function PracticeFeedbackPanel({ update, onFocusFinding }) {
  return <aside className="panel practice-feedback-panel" aria-label="自由练习反馈">
    <h2><ChevronDown aria-hidden="true" size={17} strokeWidth={1.8} />HINT 与警告</h2>
    {update ? <div aria-live="polite">
      {update.findings?.stale && <p aria-live="polite">正在后台更新检查结果…</p>}
      {update.findings?.items?.length ? <ul>{update.findings.items.map((finding, index) =>
        <PracticeFindingItem key={`${finding.messageKey}-${finding.ruleId ?? index}`}
          finding={finding} index={index} onFocusFinding={onFocusFinding} />)}</ul> : <p>未发现问题</p>}
    </div> : <p>共享内核的检查结果将在这里显示。</p>}
  </aside>;
}
