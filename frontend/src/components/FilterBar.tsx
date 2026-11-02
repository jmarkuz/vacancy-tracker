import { useState } from "preact/hooks";
import { type Criterion, describe, FIELDS, specFor, toValue, type ValueKind } from "../domain/criteria";

interface Props {
  criteria: Criterion[];
  onChange: (next: Criterion[]) => void;
}

/** Filter chips plus an add form. Every chip is one criterion; the list is stored in the URL. */
export function FilterBar({ criteria, onChange }: Props) {
  const [field, setField] = useState(FIELDS[0].field);
  const spec = specFor(field) ?? FIELDS[0];
  const [operator, setOperator] = useState(spec.operators[0].op);
  const [text, setText] = useState("");
  const [formError, setFormError] = useState<string | null>(null);

  const op = spec.operators.find((o) => o.op === operator) ?? spec.operators[0];

  const add = () => {
    const value = toValue(op.value as ValueKind, text);
    if (op.value !== "none" && value === undefined) {
      setFormError("Enter a value for this filter.");
      return;
    }
    const next: Criterion = value === undefined ? { field, operator: op.op } : { field, operator: op.op, value };
    onChange([...criteria, next]);
    setText("");
    setFormError(null);
  };

  return (
    <div class="filter-bar" role="group" aria-label="Filters">
      {criteria.length > 0 && (
        <ul class="chips" aria-label="Active filters">
          {criteria.map((c, i) => (
            <li key={i} class="chip">
              <span>{describe(c)}</span>
              <button
                type="button"
                class="chip-remove"
                aria-label={`Remove filter: ${describe(c)}`}
                onClick={() => onChange(criteria.filter((_, j) => j !== i))}
              >
                ×
              </button>
            </li>
          ))}
          <li>
            <button type="button" class="btn ghost" onClick={() => onChange([])}>
              Clear all
            </button>
          </li>
        </ul>
      )}

      <form
        class="filter-form"
        onSubmit={(e) => {
          e.preventDefault();
          add();
        }}
      >
        <label>
          <span class="sr-only">Field</span>
          <select
            class="input"
            value={field}
            onChange={(e) => {
              const next = specFor((e.target as HTMLSelectElement).value) ?? FIELDS[0];
              setField(next.field);
              setOperator(next.operators[0].op);
              setText("");
            }}
          >
            {FIELDS.map((f) => (
              <option key={f.field} value={f.field}>
                {f.label}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span class="sr-only">Operator</span>
          <select class="input" value={op.op} onChange={(e) => setOperator((e.target as HTMLSelectElement).value)}>
            {spec.operators.map((o) => (
              <option key={o.op} value={o.op}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        {op.value !== "none" && (
          <label>
            <span class="sr-only">Value</span>
            <input
              class="input"
              type={op.value === "date" ? "datetime-local" : op.value === "number" ? "number" : "text"}
              placeholder={op.value === "list" ? "comma, separated" : undefined}
              value={text}
              onInput={(e) => setText((e.target as HTMLInputElement).value)}
              aria-invalid={formError ? "true" : undefined}
            />
          </label>
        )}
        <button class="btn" type="submit">
          Add filter
        </button>
      </form>
      {formError && (
        <p class="field-error" role="alert">
          {formError}
        </p>
      )}
    </div>
  );
}
