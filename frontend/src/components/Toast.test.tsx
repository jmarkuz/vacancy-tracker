import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ACTION_TOAST_MS, INFO_TOAST_MS, Toast } from "./Toast";

let host: HTMLElement;

beforeEach(() => {
  vi.useFakeTimers();
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
  vi.useRealTimers();
});

describe("Toast", () => {
  it("dismisses an informational message after a while", () => {
    const onDismiss = vi.fn();
    act(() => render(<Toast message="Saved" tone="info" onDismiss={onDismiss} />, host));

    act(() => {
      vi.advanceTimersByTime(INFO_TOAST_MS - 1);
    });
    expect(onDismiss).not.toHaveBeenCalled();
    act(() => {
      vi.advanceTimersByTime(2);
    });
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it("keeps an error until the user dismisses it", () => {
    const onDismiss = vi.fn();
    act(() => render(<Toast message="It failed" tone="error" onDismiss={onDismiss} />, host));

    act(() => {
      vi.advanceTimersByTime(INFO_TOAST_MS * 5);
    });
    expect(onDismiss).not.toHaveBeenCalled();
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("It failed");
  });

  it("dismisses on click and shows the link", () => {
    const onDismiss = vi.fn();
    act(() =>
      render(<Toast message="+3 new" tone="info" link={{ href: "#/inbox", label: "Open Inbox" }} onDismiss={onDismiss} />, host),
    );

    expect(host.querySelector("a")?.getAttribute("href")).toBe("#/inbox");
    act(() => {
      host.querySelector("button")?.click();
    });
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });
});

const show = (onDismiss: () => void, onClick: () => void = () => undefined) =>
  act(() => {
    render(<Toast message="Done" tone="info" action={{ label: "Undo", onClick }} onDismiss={onDismiss} />, host);
  });

describe("Toast timer with an action", () => {
  it("is not restarted when the parent renders again with a new action object", () => {
    const onDismiss = vi.fn();
    show(onDismiss);
    act(() => { vi.advanceTimersByTime(ACTION_TOAST_MS - 1000); });
    show(onDismiss);
    act(() => { vi.advanceTimersByTime(1000); });
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it("calls the latest onClick", () => {
    const first = vi.fn();
    const second = vi.fn();
    show(vi.fn(), first);
    show(vi.fn(), second);
    act(() => { (host.querySelector("button.btn") as HTMLButtonElement).click(); });
    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(1);
  });

  it("stands still while the pointer is on the toast and restarts after it leaves", () => {
    const onDismiss = vi.fn();
    show(onDismiss);
    const toast = host.querySelector(".toast") as HTMLElement;
    act(() => { toast.dispatchEvent(new MouseEvent("mouseenter")); });
    act(() => { vi.advanceTimersByTime(ACTION_TOAST_MS * 2); });
    expect(onDismiss).not.toHaveBeenCalled();
    act(() => { toast.dispatchEvent(new MouseEvent("mouseleave")); });
    act(() => { vi.advanceTimersByTime(ACTION_TOAST_MS); });
    expect(onDismiss).toHaveBeenCalledTimes(1);
  });

  it("stands still while a button in the toast has focus", () => {
    const onDismiss = vi.fn();
    show(onDismiss);
    const button = host.querySelector("button.btn") as HTMLButtonElement;
    act(() => { button.dispatchEvent(new FocusEvent("focusin", { bubbles: true })); });
    act(() => { vi.advanceTimersByTime(ACTION_TOAST_MS * 2); });
    expect(onDismiss).not.toHaveBeenCalled();
  });
});
