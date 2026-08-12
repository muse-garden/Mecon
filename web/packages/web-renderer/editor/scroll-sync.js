/** Reconciles a native scrollbar with a shared controlled offset without stale-prop feedback. */
export function createControlledScrollSync(tolerance = 0.5) {
  let pending = null;
  let programmatic = null;

  return {
    apply(element, controlled) {
      if (!element) return;
      // The native thumb stays authoritative until React acknowledges its latest position.
      if (pending != null) {
        if (Math.abs(pending - controlled) <= tolerance) pending = null;
        else return;
      }
      if (Math.abs(element.scrollLeft - controlled) <= tolerance) return;
      programmatic = controlled;
      element.scrollLeft = controlled;
    },

    /** True only when this position must be reported to the parent and sibling surfaces. */
    observe(next, controlled = null) {
      if (programmatic != null) {
        const applied = programmatic;
        programmatic = null;
        if (Math.abs(next - applied) <= tolerance) return false;
      }
      // Browsers may emit a no-op scroll event at an extent boundary. Reporting the same value does
      // not make React render, so marking it pending would wait for an acknowledgement that can
      // never arrive and block every later sibling-surface update.
      if (controlled != null && Math.abs(next - controlled) <= tolerance) return false;
      pending = next;
      return true;
    },
  };
}
