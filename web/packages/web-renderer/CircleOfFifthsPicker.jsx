import React, { useMemo } from "react";

const MODES = ["MAJOR", "MINOR"];
const CIRCLE_SLOT_COUNT = 12;

function keyId(key) {
  return key ? `${key.fifths}:${key.mode}` : "";
}

function sameKey(left, right) {
  return keyId(left) === keyId(right);
}

function angleForCircleSlot(slot) {
  return -Math.PI / 2 + slot * ((2 * Math.PI) / CIRCLE_SLOT_COUNT);
}

function fifthsForCircleSlot(slot) {
  return Array.from({ length: 15 }, (_, index) => index - 7)
    .filter((fifths) => ((fifths % CIRCLE_SLOT_COUNT) + CIRCLE_SLOT_COUNT) % CIRCLE_SLOT_COUNT === slot)
    .sort((left, right) => right - left);
}

function KeyNode({ option, currentKey, selectedKeys, matchedKeys, label, onKeyClick }) {
  const current = sameKey(option.key, currentKey);
  const selected = selectedKeys.some((key) => sameKey(key, option.key));
  const matched = matchedKeys.some((key) => sameKey(key, option.key));
  const className = [
    "circle-of-fifths-key",
    current && "current",
    selected && "selected",
    matched && "matched",
  ].filter(Boolean).join(" ");
  return <button type="button" className={className} aria-label={label(option)}
    aria-pressed={current || selected} onClick={() => onKeyClick(option.key)}>
    {label(option)}
  </button>;
}

/**
 * Framework-neutral geometry for the desktop-style fifth-circle key picker.
 *
 * The caller owns key-domain conversion and labels. `options` are presentation-ready
 * `{ key: { fifths, mode }, label }` values, so this component can be reused by any
 * browser surface without importing music-domain logic.
 */
export function CircleOfFifthsPicker({
  options = [],
  currentKey = null,
  selectedKeys = [],
  matchedKeys = [],
  onKeyClick = () => {},
  label = (option) => option.label ?? "",
  className = "",
  centerLabel = null,
  centerCaption = null,
}) {
  const optionByKey = useMemo(
    () => new Map(options.map((option) => [keyId(option.key), option])),
    [options],
  );
  const nodes = MODES.flatMap((mode) => Array.from({ length: CIRCLE_SLOT_COUNT }, (_, slot) => {
    const keys = fifthsForCircleSlot(slot)
      .map((fifths) => optionByKey.get(`${fifths}:${mode}`))
      .filter(Boolean);
    if (!keys.length) return null;
    const angle = angleForCircleSlot(slot);
    const representativeFifths = keys[0].key.fifths;
    const isMajor = mode === "MAJOR";
    const staggerDirection = ((representativeFifths + 7) % 2 === 0) ? -1 : 1;
    const radius = isMajor
      ? 38.5
      : 25.5 + staggerDirection * 1.5 * Math.abs(Math.cos(angle));
    return {
      key: `${mode}:${slot}`,
      keys,
      mode,
      left: 50 + Math.cos(angle) * radius,
      top: 50 + Math.sin(angle) * radius,
    };
  }).filter(Boolean));

  return <div className={`circle-of-fifths ${className}`.trim()} aria-label="调性五度圈">
    <svg className="circle-of-fifths-guides" viewBox="0 0 100 100" aria-hidden="true">
      <circle cx="50" cy="50" r="38.5" />
      <circle cx="50" cy="50" r="25.5" />
      {Array.from({ length: CIRCLE_SLOT_COUNT }, (_, slot) => {
        const angle = angleForCircleSlot(slot);
        return <line key={slot} x1="50" y1="50" x2={50 + Math.cos(angle) * 38.5}
          y2={50 + Math.sin(angle) * 38.5} />;
      })}
    </svg>
    {nodes.map((node) => <div key={node.key} className={`circle-of-fifths-node ${node.mode.toLowerCase()}`}
      style={{ left: `${node.left}%`, top: `${node.top}%` }}>
      {node.keys.length === 1 ? <KeyNode option={node.keys[0]} currentKey={currentKey}
        selectedKeys={selectedKeys} matchedKeys={matchedKeys} label={label} onKeyClick={onKeyClick} />
        : <div className="circle-of-fifths-split-node">
          {node.keys.map((option) => <KeyNode key={keyId(option.key)} option={option}
            currentKey={currentKey} selectedKeys={selectedKeys} matchedKeys={matchedKeys}
            label={label} onKeyClick={onKeyClick} />)}
        </div>}
    </div>)}
    {(centerLabel != null || centerCaption != null) && <div className="circle-of-fifths-center">
      {centerLabel != null && <strong>{centerLabel}</strong>}
      {centerCaption != null && <small>{centerCaption}</small>}
    </div>}
  </div>;
}
