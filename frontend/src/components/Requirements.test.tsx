import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { MatchItem } from "../api/analysis";
import { Requirements } from "./AnalysisPanel";

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
});

const item = (over: Partial<MatchItem>): MatchItem => ({
  requirementId: "r1",
  text: "Java 25",
  priority: "must",
  status: "covered",
  quote: null,
  note: null,
  ...over,
});

const show = async (items: MatchItem[]) => {
  await act(async () => {
    render(<Requirements items={items} />, host);
  });
};

describe("Requirements", () => {
  it("gives each requirement a head with the status and priority in words, then its text", async () => {
    await show([item({ status: "partial", priority: "important", text: "Spring Boot" })]);

    const li = host.querySelector("li.req.req-partial") as HTMLElement;
    expect(li.querySelector(".req-head .req-status")?.textContent).toBe("Partial");
    expect(li.querySelector(".req-head")?.textContent).toContain("Important");
    expect(li.querySelector(".req-text")?.textContent).toBe("Spring Boot");
  });

  it("puts the proof in a quote of its own, apart from the requirement text", async () => {
    await show([item({ quote: "Led a Java team", note: "Only Java 21" })]);

    expect(host.querySelector("blockquote.req-quote")?.textContent).toBe("Led a Java team");
    expect(host.querySelector(".req-note")?.textContent).toBe("Only Java 21");
    expect(host.querySelector(".req-text")?.textContent).not.toContain("Led a Java team");
  });

  it("leaves out the quote and the note when there are none", async () => {
    await show([item({}), item({ requirementId: "r2", status: "missing" })]);

    expect(host.querySelectorAll("li.req").length).toBe(2);
    expect(host.querySelector(".req-quote")).toBeNull();
    expect(host.querySelector(".req-note")).toBeNull();
  });
});
