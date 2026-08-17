import React, { useEffect, useRef } from "react";
import { ORGAN_PRESETS, PlaybackInstrument } from "./rhody-playback.js";

export interface AudioSettings {
  instrument: string;
  organPreset: number;
  reverbEnabled: boolean;
}

interface AudioSettingsDialogProps {
  settings: AudioSettings;
  onChange: (settings: AudioSettings) => void;
  onClose: () => void;
}

export function AudioSettingsDialog({ settings, onChange, onClose }: AudioSettingsDialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    dialogRef.current?.showModal();
    return () => { if (dialogRef.current?.open) dialogRef.current.close(); };
  }, []);
  const roomLabel = settings.instrument === PlaybackInstrument.organ ? "教堂" : "录音室";
  return <dialog ref={dialogRef} className="audio-settings-dialog" aria-modal="true"
    aria-labelledby="audio-settings-title" onCancel={(event) => {
      event.preventDefault();
      onClose();
    }}>
    <form method="dialog" onSubmit={onClose}>
      <header>
        <h2 id="audio-settings-title">播放音色</h2>
        <button type="submit" className="dialog-close-button" aria-label="关闭">×</button>
      </header>
      <fieldset>
        <legend>音色</legend>
        {[
          [PlaybackInstrument.default, "默认合成器"],
          [PlaybackInstrument.piano, "钢琴"],
          [PlaybackInstrument.organ, "管风琴"],
        ].map(([value, label]) => <label className="audio-radio-row" key={value}>
          <input type="radio" name="instrument" value={value}
            checked={settings.instrument === value}
            onChange={() => onChange({ ...settings, instrument: value })} />
          <span>{label}</span>
        </label>)}
      </fieldset>
      {settings.instrument === PlaybackInstrument.organ && <label className="audio-settings-field">
        <span>音栓组合</span>
        <select value={settings.organPreset}
          onChange={(event) => onChange({ ...settings, organPreset: Number(event.target.value) })}>
          {ORGAN_PRESETS.map((preset: { value: number; label: string }) =>
            <option value={preset.value} key={preset.value}>{preset.label}</option>)}
        </select>
      </label>}
      {settings.instrument !== PlaybackInstrument.default && <label className="audio-reverb-row">
        <span><strong>混响</strong><small>{roomLabel}配置</small></span>
        <input type="checkbox" checked={settings.reverbEnabled}
          onChange={(event) => onChange({ ...settings, reverbEnabled: event.target.checked })} />
      </label>}
      <p className="audio-settings-hint">Rhody 未配置或加载失败时，将自动使用默认合成器。</p>
      <footer><button type="submit">完成</button></footer>
    </form>
  </dialog>;
}

export function LoadingOverlay({ message }: { message: string }) {
  return <div className="app-loading-overlay" role="progressbar" aria-label={message}
    aria-live="polite" aria-busy="true">
    <span className="app-loading-spinner" aria-hidden="true" />
    <span>{message}</span>
  </div>;
}
