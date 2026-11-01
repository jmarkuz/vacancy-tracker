const KEY = "vt.wizardSkipped";

/** Whether the user chose to skip the first-run wizard. Storage may be unavailable, so this never throws. */
export function wizardSkipped(): boolean {
  try {
    return window.localStorage.getItem(KEY) === "1";
  } catch {
    return false;
  }
}

export function skipWizard(): void {
  try {
    window.localStorage.setItem(KEY, "1");
  } catch {
    // The wizard is offered again next time; nothing else depends on this.
  }
}
