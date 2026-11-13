import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { pure } from "./pure";

let host: HTMLElement;
afterEach(() => {
  render(null, host);
  host.remove();
});

describe("pure", () => {
  it("draws again only when a prop changed", async () => {
    host = document.createElement("div");
    document.body.appendChild(host);
    let draws = 0;
    const Row = pure((p: { label: string; selected: boolean }) => {
      draws += 1;
      return <p>{p.label}{p.selected ? " *" : ""}</p>;
    });
    const draw = async (selected: boolean, label = "one") => {
      await act(async () => {
        render(<Row label={label} selected={selected} />, host);
      });
    };

    await draw(false);
    await draw(false);
    expect(draws).toBe(1);

    await draw(true);
    expect(draws).toBe(2);
    expect(host.textContent).toBe("one *");

    await draw(true, "two");
    expect(draws).toBe(3);
    expect(host.textContent).toBe("two *");
  });
});
