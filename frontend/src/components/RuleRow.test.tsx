import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Criterion, Meta } from "../api/profiles";
import { RuleRow } from "./RuleRow";

const meta = {
  fields: [
    { name: "title", kind: "TEXT", operators: [{ name: "contains", valueShape: "TEXT" }, { name: "in", valueShape: "LIST" }] },
    { name: "salary", kind: "SALARY", operators: [{ name: "gte", valueShape: "SALARY" }] },
  ],
  modes: ["REQUIRE", "BOOST"],
  missingValuePolicies: ["IGNORE", "REJECT"],
  minWeight: 0,
  maxWeight: 10,
  defaultThresholds: {},
} as unknown as Meta;

const rule = (extra: Partial<Criterion> = {}): Criterion => ({
  id: "c1",
  field: "title",
  operator: "contains",
  value: "react",
  mode: "REQUIRE",
  weight: 5,
  missingValuePolicy: "IGNORE",
  label: "Title",
  ...extra,
});

let host: HTMLElement;
let handlers: { onChange: ReturnType<typeof vi.fn>; onMove: ReturnType<typeof vi.fn>; onDuplicate: ReturnType<typeof vi.fn>; onRemove: ReturnType<typeof vi.fn> };

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
  handlers = { onChange: vi.fn(), onMove: vi.fn(), onDuplicate: vi.fn(), onRemove: vi.fn() };
});
afterEach(() => {
  render(null, host);
  host.remove();
});

const mount = async (r: Criterion = rule(), index = 1, count = 3, errors: Record<string, string> = {}) => {
  await act(async () => {
    render(<RuleRow rule={r} index={index} count={count} meta={meta} errors={errors} onChange={handlers.onChange as never} onMove={handlers.onMove as never} onDuplicate={handlers.onDuplicate as never} onRemove={handlers.onRemove as never} />, host);
  });
};

const el = <T extends HTMLElement>(sel: string) => host.querySelector<T>(sel)!;

describe("RuleRow", () => {
  it("names the rule by its place and label", async () => {
    await mount();
    expect(el<HTMLElement>('[role="group"]').getAttribute("aria-label")).toBe("Rule 2 of 3: Title");
  });

  it("changes the label", async () => {
    await mount();
    await act(async () => {
      const input = el<HTMLInputElement>('input[aria-label="Label"]');
      input.value = "Stack";
      input.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(handlers.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ label: "Stack" }));
  });

  it("changes the field and the operator", async () => {
    await mount();
    await act(async () => {
      const field = el<HTMLSelectElement>('select[aria-label="Field"]');
      field.value = "salary";
      field.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(handlers.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ field: "salary" }));

    await act(async () => {
      const op = el<HTMLSelectElement>('select[aria-label="Operator"]');
      op.value = "in";
      op.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(handlers.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ operator: "in" }));
  });

  it("changes the mode and the missing-value policy", async () => {
    await mount();
    await act(async () => {
      const mode = el<HTMLSelectElement>('select[aria-label="Mode"]');
      mode.value = "BOOST";
      mode.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(handlers.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ mode: "BOOST" }));

    await act(async () => {
      const policy = el<HTMLSelectElement>('select[aria-label="When the value is missing"]');
      policy.value = "REJECT";
      policy.dispatchEvent(new Event("change", { bubbles: true }));
    });
    expect(handlers.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ missingValuePolicy: "REJECT" }));
  });

  it("changes the weight and treats an emptied weight as missing", async () => {
    await mount();
    await act(async () => {
      const w = el<HTMLInputElement>('input[aria-label="Weight"]');
      w.value = "8";
      w.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(handlers.onChange).toHaveBeenLastCalledWith(expect.objectContaining({ weight: 8 }));

    await act(async () => {
      const w = el<HTMLInputElement>('input[aria-label="Weight"]');
      w.value = "";
      w.dispatchEvent(new Event("input", { bubbles: true }));
    });
    expect(Number.isNaN(handlers.onChange.mock.lastCall?.[0].weight)).toBe(true);
  });

  it("moves, duplicates and removes the rule from its buttons", async () => {
    await mount();

    await act(async () => {
      el<HTMLButtonElement>('button[data-role="move-up"]').click();
      el<HTMLButtonElement>('button[data-role="move-down"]').click();
      el<HTMLButtonElement>('button[data-role="duplicate"]').click();
      el<HTMLButtonElement>('button[data-role="remove"]').click();
    });

    expect(handlers.onMove).toHaveBeenCalledWith(-1, "move-up");
    expect(handlers.onMove).toHaveBeenCalledWith(1, "move-down");
    expect(handlers.onDuplicate).toHaveBeenCalledTimes(1);
    expect(handlers.onRemove).toHaveBeenCalledTimes(1);
  });

  it("does not move the first rule up or the last rule down", async () => {
    await mount(rule(), 0, 1);
    expect(el<HTMLButtonElement>('button[data-role="move-up"]').disabled).toBe(true);
    expect(el<HTMLButtonElement>('button[data-role="move-down"]').disabled).toBe(true);
  });

  it("moves the rule with Alt+Arrow from a control inside it", async () => {
    await mount();

    await act(async () => {
      el<HTMLElement>('input[aria-label="Label"]').dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", altKey: true, bubbles: true }));
    });
    expect(handlers.onMove).toHaveBeenLastCalledWith(1, "label");

    await act(async () => {
      el<HTMLElement>('select[aria-label="Mode"]').dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowUp", altKey: true, bubbles: true }));
    });
    expect(handlers.onMove).toHaveBeenLastCalledWith(-1, "mode");
  });

  it("ignores arrows without Alt", async () => {
    await mount();
    await act(async () => {
      el<HTMLElement>('input[aria-label="Label"]').dispatchEvent(new KeyboardEvent("keydown", { key: "ArrowDown", bubbles: true }));
    });
    expect(handlers.onMove).not.toHaveBeenCalled();
  });

  it("lists what is wrong with the rule", async () => {
    await mount(rule(), 0, 1, { value: "Not a number", row: "Pick a field" });

    const messages = host.querySelector('[role="alert"]')!;
    expect(messages.textContent).toContain("value: Not a number");
    expect(messages.textContent).toContain("Pick a field");
  });
});
