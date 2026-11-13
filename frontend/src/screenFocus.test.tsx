import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { useScreenAnnouncement } from "./screenFocus";

function Probe({ screen, title, ready = true }: { screen: string; title: string; ready?: boolean }) {
  useScreenAnnouncement(screen, title, ready);
  return <main id="content" tabIndex={-1} />;
}

let host: HTMLElement;
beforeEach(() => {
  document.title = "";
  host = document.createElement("div");
  document.body.appendChild(host);
});
afterEach(() => {
  render(null, host);
  host.remove();
});

const show = (screen: string, title: string, ready = true) =>
  act(() => {
    render(<Probe screen={screen} title={title} ready={ready} />, host);
  });

describe("useScreenAnnouncement", () => {
  it("sets the title on first load without moving focus", () => {
    show("dashboard", "Dashboard");
    expect(document.title).toBe("Dashboard - Vacancy Tracker");
    expect(document.activeElement).toBe(document.body);
  });

  it("sets the title and focuses the content when the screen changes", () => {
    show("dashboard", "Dashboard");
    show("inbox", "Inbox");
    expect(document.title).toBe("Inbox - Vacancy Tracker");
    expect(document.activeElement).toBe(host.querySelector("#content"));
  });

  it("leaves focus alone when the screen stays the same", () => {
    show("inbox", "Inbox");
    show("inbox", "Inbox");
    expect(document.activeElement).toBe(document.body);
  });

  it("does nothing before the app is ready", () => {
    show("dashboard", "Dashboard", false);
    expect(document.title).toBe("");
  });
});
