import {
  completeNoteResumeOffset,
  createPlaybackCursorStore,
  interpolateScoreTime,
  playbackAnchorForCursor,
  playbackRangeForSelection,
  playbackTickAtOffset,
} from "@mecon/web-renderer/editor";
import { createRhodyPlayback, PlaybackInstrument } from "./rhody-playback.js";
import type { AudioSettings } from "./AudioSettingsDialog.tsx";

interface PlaybackWorker {
  postMessage(message: unknown): void;
}

interface PracticePlaybackDependencies {
  worker: () => PlaybackWorker | null;
  frame: () => any;
  practiceUpdate: () => any;
  latestFrame: () => any;
}

interface PlaybackExcerptMessage {
  playbackRequestId: number;
  excerpt: any;
  range: any;
  trackCursor?: boolean;
}

/** Owns the browser audio graph, playback request generations and cursor animation. */
export class PracticePlaybackController {
  readonly cursorStore = createPlaybackCursorStore();

  private context: AudioContext | null = null;
  private nodes: OscillatorNode[] = [];
  private rhody: any = null;
  private scheduleId = 0;
  private animationFrame: number | null = null;
  private requestId = 0;
  private activePlayback: any = null;
  private rate = 1;
  private settings: AudioSettings;
  private readonly trace: Array<Record<string, unknown>> = [];

  constructor(
    private readonly dependencies: PracticePlaybackDependencies,
    initialSettings: AudioSettings,
  ) {
    this.settings = initialSettings;
  }

  get activeRange() {
    return this.activePlayback?.range ?? null;
  }

  traceSnapshot() {
    return [...this.trace];
  }

  setRate(rate: number) {
    this.rate = rate;
  }

  updateSettings(settings: AudioSettings) {
    this.stop();
    this.rhody?.backend?.dispose();
    this.rhody = null;
    this.settings = settings;
  }

  toggle() {
    const state = this.cursorStore.getSnapshot().state;
    if (state === "playing") this.pause();
    else if (state === "paused") this.resume();
    else this.requestTimeline(false);
  }

  requestTimeline(selectedOnly = false) {
    const update = this.dependencies.practiceUpdate();
    const slots = update?.timeline?.slots ?? [];
    if (!slots.length) return;
    this.ensureContext();
    void this.context?.resume();
    const tempoBpm = update.document.settings.writing.playbackTempoBpm;
    const first = slots[0];
    const last = slots.at(-1);
    const range = selectedOnly
      ? playbackRangeForSelection(this.dependencies.frame(), update, tempoBpm)
      : {
        firstSlotId: first.id,
        lastSlotId: last.id,
        start: first.onset,
        end: update.timeline.end ?? addFraction(last.onset, last.duration),
        tempoBpm,
      };
    if (!range) return;
    this.stop();
    this.dependencies.worker()?.postMessage({
      type: "playback", range, playbackRequestId: this.requestId,
    });
  }

  requestEdit(request: any) {
    if (!request) return;
    if (request.type === "audition" && this.cursorStore.getSnapshot().state === "playing") return;
    this.record("edit-playback", { type: request.type });
    this.ensureContext();
    void this.context?.resume();
    if (request.type === "excerpt") {
      this.stop();
      this.dependencies.worker()?.postMessage({
        type: "playback", range: request.range,
        playbackRequestId: this.requestId,
        trackCursor: false,
      });
      return;
    }
    if (request.type !== "audition" || !request.midiNumbers?.length) return;
    // A local audition supersedes a still-pending worker excerpt. Advance the request generation
    // before scheduling it so a late worker response cannot replace the note the user just chose.
    this.stop();
    const durationSeconds = 0.45;
    this.playExcerpt({
      notes: request.midiNumbers.map((midiNumber: number) => ({
        midiNumber, velocity: 88, startSeconds: 0, durationSeconds,
      })),
      durationSeconds,
      startTick: 0,
      endTick: 1,
      secondsPerTick: durationSeconds,
    }, null);
  }

  handleExcerpt(message: PlaybackExcerptMessage) {
    if (message.playbackRequestId !== this.requestId) return;
    this.playExcerpt(message.excerpt, message.trackCursor === false ? null : message.range);
  }

  stop(invalidateRequest = true) {
    if (invalidateRequest) this.requestId++;
    this.clearScheduled();
    this.activePlayback = null;
    this.cursorStore.set({ state: "idle", time: null, tick: null });
  }

  dispose() {
    this.stop();
    this.rhody?.backend?.dispose();
    this.rhody = null;
    void this.context?.close();
    this.context = null;
  }

  private ensureContext() {
    if (!this.context) this.context = new AudioContext();
  }

  private record(kind: string, details: Record<string, unknown>) {
    const entry = { kind, atMs: performance.now(), ...details };
    this.trace.push(entry);
    if (this.trace.length > 120) this.trace.shift();
    if (import.meta.env.DEV || import.meta.env.MODE === "e2e") {
      console.debug(`[mecon-playback] ${kind}`, entry);
    }
  }

  private clearScheduled() {
    this.scheduleId++;
    if (this.animationFrame != null) cancelAnimationFrame(this.animationFrame);
    this.animationFrame = null;
    this.rhody?.backend?.reset();
    for (const node of this.nodes) {
      try { node.stop(); } catch { /* already stopped */ }
      node.disconnect();
    }
    this.nodes = [];
  }

  private playExcerpt(excerpt: any, range: any) {
    if (!this.context || (range && (!range.start || !range.end))) return;
    this.stop(false);
    const playback = { excerpt, range, offsetSeconds: 0, baseContextTime: null, rate: 1 };
    this.activePlayback = playback;
    void this.schedule(playback);
  }

  private async rhodyBackend() {
    if (!this.context || this.settings.instrument === PlaybackInstrument.default) return null;
    const key = JSON.stringify(this.settings);
    if (this.rhody?.key === key) return this.rhody.promise;
    this.rhody?.backend?.dispose();
    const entry: any = { key, backend: null, promise: null };
    entry.promise = createRhodyPlayback(this.context, {
      ...this.settings,
      onDiagnostic: ((diagnostic: Record<string, unknown>) => {
        this.record("rhody-worklet", diagnostic);
      }) as () => void,
    }).then((backend: any) => {
      if (this.rhody !== entry) {
        backend?.dispose();
        return null;
      }
      entry.backend = backend;
      return backend;
    }).catch((error: Error) => {
      this.record("rhody-fallback", { message: error.message });
      return null;
    });
    this.rhody = entry;
    return entry.promise;
  }

  private async schedule(playback: any) {
    const context = this.context;
    if (!context) return;
    this.clearScheduled();
    const scheduleId = this.scheduleId;
    const settings = this.settings;
    const rhody = await this.rhodyBackend();
    if (scheduleId !== this.scheduleId || this.activePlayback !== playback) return;
    const base = context.currentTime + 0.025;
    const rate = this.rate;
    const excerptDuration = Math.max(0, Number(playback.excerpt.durationSeconds));
    playback.baseContextTime = base;
    playback.rate = rate;
    playback.lastLoggedAnchorTick = null;
    this.record("schedule", {
      contextTime: context.currentTime, baseContextTime: base, rate,
      startTick: playback.excerpt.startTick, endTick: playback.excerpt.endTick,
      secondsPerTick: playback.excerpt.secondsPerTick,
      durationSeconds: excerptDuration,
      noteStarts: [...new Set(playback.excerpt.notes.map((note: any) => Number(note.startSeconds)))],
    });
    const playableNotes = playback.excerpt.notes.filter((note: any) => (
      Number(note.startSeconds) + Number(note.durationSeconds) > playback.offsetSeconds
    ));
    if (rhody) {
      for (const note of playableNotes) {
        const noteEndSeconds = Number(note.startSeconds) + Number(note.durationSeconds);
        const audibleStart = Math.max(Number(note.startSeconds), playback.offsetSeconds);
        const start = base + Math.max(0, Number(note.startSeconds) - playback.offsetSeconds) / rate;
        const end = start + Math.max(0.03, (noteEndSeconds - audibleStart) / rate);
        const frequency = 440 * (2 ** ((note.midiNumber - 69) / 12));
        rhody.noteOn(frequency, Math.max(0.01, Math.min(1, Number(note.velocity) / 127)), start);
        rhody.noteOff(frequency, end);
      }
      this.record("rhody-schedule", {
        instrument: settings.instrument,
        organPreset: settings.organPreset,
        roomPreset: rhody.roomPreset,
      });
    }
    this.nodes = rhody ? [] : playableNotes.map((note: any) => {
      const noteEndSeconds = Number(note.startSeconds) + Number(note.durationSeconds);
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.type = "triangle";
      oscillator.frequency.value = 440 * (2 ** ((note.midiNumber - 69) / 12));
      const audibleStart = Math.max(Number(note.startSeconds), playback.offsetSeconds);
      const start = base + Math.max(0, Number(note.startSeconds) - playback.offsetSeconds) / rate;
      const end = start + Math.max(0.03, (noteEndSeconds - audibleStart) / rate);
      const level = Math.max(0.015, Math.min(0.12, note.velocity / 900));
      gain.gain.setValueAtTime(0, start);
      gain.gain.linearRampToValueAtTime(level, start + 0.01);
      gain.gain.setValueAtTime(level, Math.max(start + 0.01, end - 0.03));
      gain.gain.linearRampToValueAtTime(0, end);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start(start);
      oscillator.stop(end + 0.01);
      return oscillator;
    });
    const advancePlayhead = () => {
      const elapsed = Math.max(0, context.currentTime - base) * rate;
      const offset = Math.min(excerptDuration, playback.offsetSeconds + elapsed);
      const progress = excerptDuration === 0 ? 1 : offset / excerptDuration;
      const time = playback.range
        ? interpolateScoreTime(playback.range.start, playback.range.end, progress)
        : null;
      const tick = playbackTickAtOffset(playback.excerpt, offset);
      if (playback.range) this.cursorStore.set({ state: "playing", time, tick });
      const anchor = playback.range
        ? playbackAnchorForCursor(this.dependencies.latestFrame(), time, tick)
        : null;
      if (anchor && anchor.tick !== playback.lastLoggedAnchorTick) {
        playback.lastLoggedAnchorTick = anchor.tick;
        this.record("cursor-anchor", {
          contextTime: context.currentTime, elapsedSeconds: elapsed, offsetSeconds: offset,
          cursorTick: tick, anchorTick: anchor.tick, scoreTime: anchor.scoreTime,
        });
      }
      if (progress < 1) this.animationFrame = requestAnimationFrame(advancePlayhead);
      else this.stop(false);
    };
    advancePlayhead();
  }

  private currentOffset(playback: any) {
    if (!this.context || playback.baseContextTime == null) return playback.offsetSeconds;
    return Math.min(
      Number(playback.excerpt.durationSeconds),
      playback.offsetSeconds
        + Math.max(0, this.context.currentTime - playback.baseContextTime) * playback.rate,
    );
  }

  private pause() {
    const playback = this.activePlayback;
    if (!playback) return;
    const currentOffset = this.currentOffset(playback);
    const resumeOffset = completeNoteResumeOffset(playback.excerpt.notes, currentOffset);
    playback.offsetSeconds = resumeOffset;
    playback.baseContextTime = null;
    this.clearScheduled();
    const duration = Number(playback.excerpt.durationSeconds);
    const pausedTime = interpolateScoreTime(
      playback.range.start,
      playback.range.end,
      duration <= 0 ? 1 : currentOffset / duration,
    );
    const pausedTick = playbackTickAtOffset(playback.excerpt, currentOffset);
    this.cursorStore.set({ state: "paused", time: pausedTime, tick: pausedTick });
    this.record("pause", {
      currentOffsetSeconds: currentOffset, resumeOffsetSeconds: resumeOffset,
      pausedTick, resumeTick: playbackTickAtOffset(playback.excerpt, resumeOffset),
      visibleAnchorTick: playbackAnchorForCursor(
        this.dependencies.latestFrame(), pausedTime, pausedTick,
      )?.tick ?? null,
    });
  }

  private resume() {
    if (!this.activePlayback) return;
    void this.context?.resume();
    void this.schedule(this.activePlayback);
  }
}

function addFraction(left: any, right: any) {
  const numerator = left.numerator * right.denominator + right.numerator * left.denominator;
  const denominator = left.denominator * right.denominator;
  const gcd = (a: number, b: number): number => (b ? gcd(b, a % b) : Math.abs(a));
  const divisor = gcd(numerator, denominator) || 1;
  return { numerator: numerator / divisor, denominator: denominator / divisor };
}
