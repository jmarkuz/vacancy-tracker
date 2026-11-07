import { describe, expect, it, vi } from "vitest";
import { GLASS_LIVE_SELECTOR, installGlassMotion, lightPosition, motionAllowed } from "./glassMotion";

const rect = { left: 100, top: 50, width: 200, height: 40 };

describe("lightPosition", () => {
  it("places the light where the pointer is, in percent of the control", () => {
    expect(lightPosition(rect, 200, 70)).toEqual({ x: 50, y: 50 });
    expect(lightPosition(rect, 100, 50)).toEqual({ x: 0, y: 0 });
    expect(lightPosition(rect, 300, 90)).toEqual({ x: 100, y: 100 });
  });

  it("keeps the light inside the control when the pointer is outside it", () => {
    expect(lightPosition(rect, 0, 0)).toEqual({ x: 0, y: 0 });
    expect(lightPosition(rect, 1000, 1000)).toEqual({ x: 100, y: 100 });
  });

  it("does not divide by zero for an element with no size", () => {
    expect(lightPosition({ left: 5, top: 5, width: 0, height: 0 }, 6, 6)).toEqual({ x: 100, y: 100 });
  });
});

describe("motionAllowed", () => {
  const base = { reducedMotion: false, glass: null, coarsePointer: false };

  it("runs with a mouse, the glass on and no request for less motion", () => {
    expect(motionAllowed(base)).toBe(true);
  });

  it("stops for reduced motion, for Glass effects off, and for touch screens", () => {
    expect(motionAllowed({ ...base, reducedMotion: true })).toBe(false);
    expect(motionAllowed({ ...base, glass: "off" })).toBe(false);
    expect(motionAllowed({ ...base, coarsePointer: true })).toBe(false);
  });
});

/** A window with a frame clock the test drives by hand. */
function fakeWindow(reduced: boolean) {
  const frames = new Map<number, FrameRequestCallback>();
  let next = 1;
  const win = {
    matchMedia: (q: string) => ({ matches: q.includes("reduce") ? reduced : false }),
    requestAnimationFrame: (cb: FrameRequestCallback) => {
      frames.set(next, cb);
      return next++;
    },
    cancelAnimationFrame: (id: number) => frames.delete(id),
  } as unknown as Window;
  const runFrames = () => {
    for (const [id, cb] of [...frames]) {
      frames.delete(id);
      cb(0);
    }
  };
  return { win, runFrames, pending: () => frames.size };
}

function setup() {
  document.body.innerHTML = `<div class="header"><button class="btn" id="b"><span id="inner">Go</span></button><p id="plain">text</p></div>`;
  const button = document.getElementById("b") as HTMLElement;
  button.getBoundingClientRect = () => ({ left: 0, top: 0, width: 100, height: 50, right: 100, bottom: 50, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
  return { button, inner: document.getElementById("inner") as HTMLElement, plain: document.getElementById("plain") as HTMLElement };
}

const move = (target: Element, x: number, y: number) =>
  target.dispatchEvent(new MouseEvent("pointermove", { clientX: x, clientY: y, bubbles: true }) as unknown as PointerEvent);

describe("installGlassMotion", () => {
  it("is limited to the live controls", () => {
    expect(GLASS_LIVE_SELECTOR.split(",").map((s) => s.trim())).toEqual([".btn", ".chip", ".tab", ".nav-item"]);
  });

  it("moves the light to the pointer on the next frame, not on every event", () => {
    const { button, inner } = setup();
    const { win, runFrames, pending } = fakeWindow(false);
    const stop = installGlassMotion(document, win);

    move(inner, 25, 10);
    move(inner, 50, 25);
    expect(pending()).toBe(1);
    expect(button.style.getPropertyValue("--lg-x")).toBe("");

    runFrames();

    expect(button.style.getPropertyValue("--lg-x")).toBe("50%");
    expect(button.style.getPropertyValue("--lg-y")).toBe("50%");
    stop();
  });

  it("ignores pointers over anything that is not a live control", () => {
    const { plain } = setup();
    const { win, pending } = fakeWindow(false);
    const stop = installGlassMotion(document, win);

    move(plain, 10, 10);

    expect(pending()).toBe(0);
    stop();
  });

  it("does nothing with reduced motion", () => {
    const { button, inner } = setup();
    const { win, runFrames, pending } = fakeWindow(true);
    const stop = installGlassMotion(document, win);

    move(inner, 25, 10);
    runFrames();

    expect(pending()).toBe(0);
    expect(button.style.getPropertyValue("--lg-x")).toBe("");
    stop();
  });

  it("resets the light when the pointer leaves the control, but not when it moves between its children", () => {
    const { button, inner } = setup();
    const { win, runFrames } = fakeWindow(false);
    const stop = installGlassMotion(document, win);
    move(inner, 25, 10);
    runFrames();
    expect(button.style.getPropertyValue("--lg-x")).toBe("25%");

    inner.dispatchEvent(new MouseEvent("pointerout", { bubbles: true, relatedTarget: button }) as unknown as PointerEvent);
    expect(button.style.getPropertyValue("--lg-x")).toBe("25%");

    button.dispatchEvent(new MouseEvent("pointerout", { bubbles: true, relatedTarget: document.body }) as unknown as PointerEvent);
    expect(button.style.getPropertyValue("--lg-x")).toBe("");
    stop();
  });

  it("stops listening and cancels a pending frame when stopped", () => {
    const { inner } = setup();
    const { win, pending } = fakeWindow(false);
    const cancel = vi.spyOn(win, "cancelAnimationFrame");
    const stop = installGlassMotion(document, win);
    move(inner, 25, 10);
    expect(pending()).toBe(1);

    stop();
    move(inner, 30, 10);

    expect(cancel).toHaveBeenCalled();
    expect(pending()).toBe(0);
  });
});
