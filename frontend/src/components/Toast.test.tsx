import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { INFO_TOAST_MS, Toast } from "./Toast";

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
