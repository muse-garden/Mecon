const LOCALIZED_FINDINGS = Object.freeze({
  "freePractice.finding.incompleteHarmony": {
    title: "和声尚未填写完整",
    detail: "空槽保持未判定，不会被误报为违规。",
  },
  "freePractice.finding.voiceRange": {
    title: "音符超出声部范围",
    detail: ({ pitch }) => `${pitch ?? "?"} 不在当前声部预设音域内。`,
  },
  "freePractice.finding.polyphonyLimit": {
    title: "同时发声音符超过上限",
    detail: ({ peak, limit }) => `当前峰值为 ${peak ?? "?"} 个音符，上限为 ${limit ?? "?"}。`,
  },
  "freePractice.finding.voiceSeparation": {
    title: "分析声部分离未完成",
    detail: "记谱保持不变；请调整交叠音符后再次分析。",
  },
});

export function practiceFindingText(finding) {
  const localized = LOCALIZED_FINDINGS[finding.messageKey];
  const localizedDetail = typeof localized?.detail === "function"
    ? localized.detail(finding.arguments ?? {})
    : localized?.detail;
  return {
    title: finding.message ?? localized?.title ??
      (finding.ruleId ? `规则 ${finding.ruleId}` : finding.messageKey),
    detail: localizedDetail ?? (finding.ruleId ? `规则：${finding.ruleId}` : ""),
  };
}
