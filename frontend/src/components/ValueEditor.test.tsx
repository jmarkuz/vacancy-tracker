import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ValueShape } from "../api/profiles";
import { ValueEditor } from "./ValueEditor";

let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});

afterEach(() => {
  render(null, host);
  host.remove();
});

const mount = async (shape: ValueShape, value: unknown, onChange = vi.fn(), extra: { errorId?: string; invalid?: boolean } = {}) => {
  await act(async () => {
    render(<ValueEditor shape={shape} value={value} onChange={onChange} {...extra} />, host);
  });
  return onChange;
};

const field = (label: string) => host.querySelector<HTMLInputElement | HTMLSelectElement>(`[aria-label="${label}"]`)!;

const type = async (el: HTMLInputElement | HTMLSelectElement, value: string) => {
  await act(async () => {
    el.value = value;
    el.dispatchEvent(new Event(el.tagName === "SELECT" ? "change" : "input", { bubbles: true }));
  });
};

describe("ValueEditor", () => {
  it("needs no value for an operator that takes none", async () => {
    await mount("NONE", null);
    expect(host.textContent).toContain("No value needed");
  });

  it("reads and writes a number, and sends null for an emptied box", async () => {
    const onChange = await mount("NUMBER", 4, vi.fn(), { invalid: true, errorId: "err-1" });
    expect((host.querySelector("input") as HTMLInputElement).value).toBe("4");
    expect(host.querySelector("input")?.getAttribute("aria-invalid")).toBe("true");
    expect(host.querySelector("input")?.getAttribute("aria-describedby")).toBe("err-1");

    await type(field("Value") as HTMLInputElement, "7");
    expect(onChange).toHaveBeenLastCalledWith(7);

    await type(field("Value") as HTMLInputElement, "");
    expect(onChange).toHaveBeenLastCalledWith(null);
  });

  it("shows an empty number box for a value that is not a number", async () => {
    await mount("NUMBER", "abc");
    expect((host.querySelector("input") as HTMLInputElement).value).toBe("");
  });

  it("edits both ends of a range", async () => {
    const onChange = await mount("RANGE", [1, 9]);

    await type(field("Minimum") as HTMLInputElement, "2");
    expect(onChange).toHaveBeenLastCalledWith([2, 9]);

    await type(field("Maximum") as HTMLInputElement, "");
    expect(onChange).toHaveBeenLastCalledWith([1, null]);
  });

  it("starts an empty range when there is no value yet", async () => {
    const onChange = await mount("RANGE", undefined);
    await type(field("Maximum") as HTMLInputElement, "5");
    expect(onChange).toHaveBeenLastCalledWith([null, 5]);
  });

  it("chooses yes or no for a boolean", async () => {
    const onChange = await mount("BOOLEAN", true);
    expect((field("Value") as HTMLSelectElement).value).toBe("true");

    await type(field("Value") as HTMLSelectElement, "false");
    expect(onChange).toHaveBeenLastCalledWith(false);
  });

  it("keeps a comma list as typed and emits the parsed list", async () => {
    const onChange = await mount("LIST", ["a", "b"]);
    const input = host.querySelector<HTMLInputElement>('[aria-label="Values, comma separated"]')!;
    expect(input.value).toBe("a, b");

    await type(input as never, "a, b, c,");
    expect(onChange).toHaveBeenLastCalledWith(["a", "b", "c"]);
    expect(input.value).toBe("a, b, c,");
  });

  it("writes a text value and uses a monospace box for a pattern", async () => {
    const onChange = await mount("TEXT", "react");
    expect(host.querySelector<HTMLInputElement>("input")!.value).toBe("react");
    await type(field("Value") as HTMLInputElement, "vue");
    expect(onChange).toHaveBeenLastCalledWith("vue");

    await act(async () => {
      render(null, host);
    });
    await mount("PATTERN", 42);
    const pattern = host.querySelector<HTMLInputElement>('[aria-label="Pattern"]')!;
    expect(pattern.value).toBe("");
    expect(pattern.classList.contains("mono")).toBe(true);
  });

  it("edits one salary amount with its currency, period and basis", async () => {
    const onChange = await mount("SALARY", { amount: 1000, currency: "usd", period: "year", basis: "gross" });
    expect((field("Amount") as HTMLInputElement).value).toBe("1000");

    await type(field("Amount") as HTMLInputElement, "1500");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ amount: 1500, currency: "usd" }));

    await type(host.querySelector<HTMLInputElement>('[aria-label="Currency code"]')!, "eur");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ currency: "EUR" }));

    await type(field("Period") as HTMLSelectElement, "month");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ period: "month" }));

    await type(field("Gross or net") as HTMLSelectElement, "");
    const last = onChange.mock.lastCall?.[0] as Record<string, unknown>;
    expect("basis" in last).toBe(false);
  });

  it("edits a salary range and keeps the other end", async () => {
    const onChange = await mount("SALARY_RANGE", { min: 1000, max: 2000, period: "month" });
    expect((field("Maximum amount") as HTMLInputElement).value).toBe("2000");

    await type(field("Minimum amount") as HTMLInputElement, "1200");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ min: 1200, max: 2000, period: "month" }));

    await type(field("Maximum amount") as HTMLInputElement, "");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ max: null }));
  });

  it("sets the basis when a salary has none", async () => {
    const onChange = await mount("SALARY", undefined);
    await type(field("Gross or net") as HTMLSelectElement, "net");
    expect(onChange).toHaveBeenLastCalledWith(expect.objectContaining({ basis: "net" }));
  });
});
