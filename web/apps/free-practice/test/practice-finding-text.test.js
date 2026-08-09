import assert from "node:assert/strict";
import test from "node:test";

import { practiceFindingText } from "../src/practice-finding-text.js";

test("rule findings show the shared explanation and keep the rule id secondary", () => {
  assert.deepEqual(practiceFindingText({
    messageKey: "freePractice.rule.free.counterpoint.parallel-perfect",
    message: "出现平行纯五度或纯八度；自由写作中保留为可调软偏好。",
    ruleId: "free.counterpoint.parallel-perfect",
  }), {
    title: "出现平行纯五度或纯八度；自由写作中保留为可调软偏好。",
    detail: "规则：free.counterpoint.parallel-perfect",
  });
});

test("built-in finding keys are localized with their arguments", () => {
  assert.deepEqual(practiceFindingText({
    messageKey: "freePractice.finding.polyphonyLimit",
    arguments: { peak: "5", limit: "4" },
  }), {
    title: "同时发声音符超过上限",
    detail: "当前峰值为 5 个音符，上限为 4。",
  });
});
