import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MatchBar } from "./MatchBar";

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});
afterEach(() => {
  render(null, host);
  host.remove();
});

const mount = async (score: number, note?: string) => {
  await act(async () => {
    render(<MatchBar label="Goal match" score={score} note={note} />, host);
  });
};

describe("MatchBar", () => {
  it("writes the score and its level out as text and as a meter", async () => {
    await mount(72);

    const meter = host.querySelector('[role="meter"]')!;
    expect(meter.getAttribute("aria-valuenow")).toBe("72");
    expect(meter.getAttribute("aria-valuetext")).toMatch(/^72% \(.+\)$/);
    expect(host.querySelector(".num")?.textContent).toMatch(/^72%/);
  });

  it("adds the note to the text for assistive technology", async () => {
    await mount(40, "from the resume");

    expect(host.querySelector('[role="meter"]')!.getAttribute("aria-valuetext")).toContain(", from the resume");
    expect(host.textContent).toContain("from the resume");
  });

  it("keeps the fill inside the track for scores out of range", async () => {
    await mount(140);
    expect((host.querySelector(".match-fill") as HTMLElement).style.width).toBe("100%");

    await mount(-5);
    expect((host.querySelector(".match-fill") as HTMLElement).style.width).toBe("0%");
  });
});
