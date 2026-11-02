import { useState } from "preact/hooks";
import type { ValueShape } from "../api/profiles";
import { formatList, parseList } from "../domain/profileEditor";

interface Props {
  shape: ValueShape;
  value: unknown;
  onChange: (value: unknown) => void;
  /** Id of the element that shows this value's error, so screen readers connect them. */
  errorId?: string;
  invalid?: boolean;
}

type Obj = Record<string, unknown>;

const num = (v: unknown): string => (typeof v === "number" && Number.isFinite(v) ? String(v) : "");
/** An empty box is null, which the server rejects as "a number is required"; a stale 0 would hide the gap. */
const toNumber = (text: string): number | null => (text.trim() === "" ? null : Number(text));

/** One value editor per value shape, so only valid kinds of input are offered for an operator. */
export function ValueEditor({ shape, value, onChange, errorId, invalid }: Props) {
  const common = { "aria-invalid": invalid ? ("true" as const) : undefined, "aria-describedby": errorId };

  switch (shape) {
    case "NONE":
      return <span class="muted small">No value needed</span>;
    case "LIST":
      return <ListInput value={value} onChange={onChange} {...common} />;
    case "NUMBER":
      return (
        <input
          class="input num"
          type="number"
          aria-label="Value"
          data-role="value"
          value={num(value)}
          onInput={(e) => onChange(toNumber((e.target as HTMLInputElement).value))}
          {...common}
        />
      );
    case "RANGE": {
      const [min, max] = Array.isArray(value) ? value : [null, null];
      return (
        <span class="value-group">
          <input class="input num" type="number" aria-label="Minimum" data-role="value" value={num(min)}
            onInput={(e) => onChange([toNumber((e.target as HTMLInputElement).value), max ?? null])} {...common} />
          <input class="input num" type="number" aria-label="Maximum" value={num(max)}
            onInput={(e) => onChange([min ?? null, toNumber((e.target as HTMLInputElement).value)])} {...common} />
        </span>
      );
    }
    case "BOOLEAN":
      return (
        <select class="input" aria-label="Value" data-role="value" value={String(value === true)}
          onChange={(e) => onChange((e.target as HTMLSelectElement).value === "true")} {...common}>
          <option value="true">Yes</option>
          <option value="false">No</option>
        </select>
      );
    case "SALARY":
    case "SALARY_RANGE":
      return <SalaryInput range={shape === "SALARY_RANGE"} value={(value ?? {}) as Obj} onChange={onChange} {...common} />;
    default:
      return (
        <input
          class={`input${shape === "PATTERN" ? " mono" : ""}`}
          type="text"
          aria-label={shape === "PATTERN" ? "Pattern" : "Value"}
          data-role="value"
          value={typeof value === "string" ? value : ""}
          onInput={(e) => onChange((e.target as HTMLInputElement).value)}
          {...common}
        />
      );
  }
}

/** Keeps the typed text while editing, so a trailing comma is not lost; emits the parsed list on every key. */
function ListInput({ value, onChange, ...aria }: { value: unknown; onChange: (v: unknown) => void } & Record<string, unknown>) {
  const [text, setText] = useState(formatList(value));
  return (
    <input
      class="input"
      type="text"
      aria-label="Values, comma separated"
      placeholder="comma, separated"
      data-role="value"
      value={text}
      onInput={(e) => {
        const next = (e.target as HTMLInputElement).value;
        setText(next);
        onChange(parseList(next));
      }}
      {...aria}
    />
  );
}

function SalaryInput({ range, value, onChange, ...aria }: { range: boolean; value: Obj; onChange: (v: unknown) => void } & Record<string, unknown>) {
  const set = (patch: Obj) => onChange({ ...value, ...patch });
  const basis = typeof value.basis === "string" ? value.basis : "";
  return (
    <span class="value-group">
      {range ? (
        <>
          <input class="input num" type="number" aria-label="Minimum amount" data-role="value" value={num(value.min)}
            onInput={(e) => set({ min: toNumber((e.target as HTMLInputElement).value) })} {...aria} />
          <input class="input num" type="number" aria-label="Maximum amount" value={num(value.max)}
            onInput={(e) => set({ max: toNumber((e.target as HTMLInputElement).value) })} {...aria} />
        </>
      ) : (
        <input class="input num" type="number" aria-label="Amount" data-role="value" value={num(value.amount)}
          onInput={(e) => set({ amount: toNumber((e.target as HTMLInputElement).value) })} {...aria} />
      )}
      <input class="input currency" type="text" aria-label="Currency code" maxLength={3} value={typeof value.currency === "string" ? value.currency : ""}
        onInput={(e) => set({ currency: (e.target as HTMLInputElement).value.toUpperCase() })} />
      <select class="input" aria-label="Period" value={typeof value.period === "string" ? value.period : "year"}
        onChange={(e) => set({ period: (e.target as HTMLSelectElement).value })}>
        <option value="year">per year</option>
        <option value="month">per month</option>
      </select>
      <select class="input" aria-label="Gross or net" value={basis}
        onChange={(e) => {
          const next = (e.target as HTMLSelectElement).value;
          const { basis: _drop, ...rest } = value;
          onChange(next === "" ? rest : { ...rest, basis: next });
        }}>
        <option value="">basis</option>
        <option value="gross">gross</option>
        <option value="net">net</option>
      </select>
    </span>
  );
}
