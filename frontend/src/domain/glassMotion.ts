/**
 * Glass motion, the part that answers the pointer. A control under the pointer gets its highlight where the
 * pointer is (the glass reacts to touch and movement). The rules here are pure so they can be tested; the
 * listeners that use them are in installGlassMotion below.
 */

/** The controls that light up under the pointer. Surfaces (sidebar, sheets) keep the static highlight. */
export const GLASS_LIVE_SELECTOR = ".btn, .chip, .tab, .nav-item";

/** Where the pointer is inside the element, in percent of its width and height, kept inside 0 to 100. */
export function lightPosition(
  rect: { left: number; top: number; width: number; height: number },
  clientX: number,
  clientY: number,
): { x: number; y: number } {
  const clamp = (v: number) => Math.min(100, Math.max(0, v));
  const w = rect.width > 0 ? rect.width : 1;
  const h = rect.height > 0 ? rect.height : 1;
  return { x: clamp(((clientX - rect.left) / w) * 100), y: clamp(((clientY - rect.top) / h) * 100) };
}

/** Motion runs only when the system does not ask for less, Glass effects are on, and the pointer is a mouse or pen. */
export function motionAllowed(env: { reducedMotion: boolean; glass: string | null; coarsePointer: boolean }): boolean {
  return !env.reducedMotion && env.glass !== "off" && !env.coarsePointer;
}

/** The screen-side part: sets --lg-x and --lg-y on the control under the pointer, once per frame. Returns the stopper. */
export function installGlassMotion(doc: Document = document, win: Window = window): () => void {
  const matches = (query: string) => (typeof win.matchMedia === "function" ? win.matchMedia(query).matches : false);
  const allowed = () =>
    motionAllowed({
      reducedMotion: matches("(prefers-reduced-motion: reduce)"),
      glass: doc.documentElement.getAttribute("data-glass"),
      coarsePointer: matches("(pointer: coarse)"),
    });
  const liveAt = (target: EventTarget | null): HTMLElement | null =>
    target instanceof Element ? (target.closest(GLASS_LIVE_SELECTOR) as HTMLElement | null) : null;

  let frame = 0;
  let pending: { el: HTMLElement; x: number; y: number } | null = null;

  const onMove = (e: PointerEvent) => {
    if (!allowed()) {
      return;
    }
    const el = liveAt(e.target);
    if (!el) {
      return;
    }
    pending = { el, x: e.clientX, y: e.clientY };
    if (frame === 0) {
      frame = win.requestAnimationFrame(() => {
        frame = 0;
        const next = pending;
        pending = null;
        if (!next) {
          return;
        }
        const p = lightPosition(next.el.getBoundingClientRect(), next.x, next.y);
        next.el.style.setProperty("--lg-x", `${p.x}%`);
        next.el.style.setProperty("--lg-y", `${p.y}%`);
      });
    }
  };

  // pointerout also fires when the pointer moves between the control's own children; only leaving it resets the light.
  const onOut = (e: PointerEvent) => {
    const el = liveAt(e.target);
    if (el && !el.contains(e.relatedTarget as Node | null)) {
      el.style.removeProperty("--lg-x");
      el.style.removeProperty("--lg-y");
    }
  };

  doc.addEventListener("pointermove", onMove, { passive: true });
  doc.addEventListener("pointerout", onOut, { passive: true });
  return () => {
    doc.removeEventListener("pointermove", onMove);
    doc.removeEventListener("pointerout", onOut);
    if (frame !== 0) {
      win.cancelAnimationFrame(frame);
      frame = 0;
    }
    pending = null;
  };
}
