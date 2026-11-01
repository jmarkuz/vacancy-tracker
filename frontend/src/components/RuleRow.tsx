import type { Criterion, Meta } from "../api/profiles";
import { changeField, changeOperator, shapeOf } from "../domain/profileEditor";
import { ValueEditor } from "./ValueEditor";

interface Props {
  rule: Criterion;
  index: number;
  count: number;
  meta: Meta;
  errors: Record<string, string>;
  onChange: (rule: Criterion) => void;
  onMove: (delta: number, role: string) => void;
  onDuplicate: () => void;
  onRemove: () => void;
}

/**
 * One rule: label, field, operator, value, mode, weight, missing-value policy. Alt+Up and Alt+Down move the
 * rule from any control inside it; the Move buttons do the same for pointer users. The row keeps the
 * `data-rule` id so focus can be restored after a move.
 */
export function RuleRow({ rule, index, count, meta, errors, onChange, onMove, onDuplicate, onRemove }: Props) {
  const field = meta.fields.find((f) => f.name === rule.field);
  const shape = shapeOf(meta, rule.field, rule.operator);
  const errorId = `rule-${rule.id}-errors`;
  const messages = Object.entries(errors);

  const onKeyDown = (e: KeyboardEvent) => {
    if (!e.altKey || (e.key !== "ArrowUp" && e.key !== "ArrowDown")) {
      return;
    }
    e.preventDefault();
    const role = (e.target as HTMLElement).dataset.role ?? "move-down";
    onMove(e.key === "ArrowUp" ? -1 : 1, role);
  };

  return (
    <li class="rule-row" role="group" aria-label={`Rule ${index + 1} of ${count}${rule.label ? `: ${rule.label}` : ""}`}
      data-rule={rule.id} onKeyDown={onKeyDown}>
      <div class="rule-line">
        <span class="rule-index num" aria-hidden="true">{index + 1}</span>

        <input class="input rule-label" type="text" aria-label="Label" data-role="label" placeholder="Label (shown in match reasons)"
          value={rule.label ?? ""} onInput={(e) => onChange({ ...rule, label: (e.target as HTMLInputElement).value })} />

        <select class="input" aria-label="Field" data-role="field" value={rule.field}
          onChange={(e) => onChange(changeField(rule, meta, (e.target as HTMLSelectElement).value))}>
          {meta.fields.map((f) => <option key={f.name} value={f.name}>{f.name}</option>)}
        </select>

        <select class="input" aria-label="Operator" data-role="operator" value={rule.operator}
          onChange={(e) => onChange(changeOperator(rule, meta, (e.target as HTMLSelectElement).value))}>
          {field?.operators.map((o) => <option key={o.name} value={o.name}>{o.name}</option>)}
        </select>

        <span class="rule-value">
          <ValueEditor key={`${rule.field}-${rule.operator}`} shape={shape} value={rule.value}
            onChange={(value) => onChange({ ...rule, value })} errorId={errorId} invalid={Boolean(errors.value)} />
        </span>

      </div>

      <div class="rule-meta">
        <select class="input" aria-label="Mode" data-role="mode" value={rule.mode}
          onChange={(e) => onChange({ ...rule, mode: (e.target as HTMLSelectElement).value })}>
          {meta.modes.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>

        <input class="input weight num" type="number" aria-label="Weight" data-role="weight" min={meta.minWeight} max={meta.maxWeight}
          value={Number.isFinite(rule.weight) ? rule.weight : ""}
          onInput={(e) => onChange({ ...rule, weight: Number((e.target as HTMLInputElement).value) })} />

        <select class="input" aria-label="When the value is missing" data-role="policy" value={rule.missingValuePolicy}
          onChange={(e) => onChange({ ...rule, missingValuePolicy: (e.target as HTMLSelectElement).value })}>
          {meta.missingValuePolicies.map((p) => <option key={p} value={p}>{`missing: ${p}`}</option>)}
        </select>

        <span class="rule-actions">
          <button type="button" class="btn" data-role="move-up" disabled={index === 0} onClick={() => onMove(-1, "move-up")}
            aria-label={`Move rule ${index + 1} up`}>↑</button>
          <button type="button" class="btn" data-role="move-down" disabled={index === count - 1} onClick={() => onMove(1, "move-down")}
            aria-label={`Move rule ${index + 1} down`}>↓</button>
          <button type="button" class="btn" data-role="duplicate" onClick={onDuplicate} aria-label={`Duplicate rule ${index + 1}`}>Duplicate</button>
          <button type="button" class="btn ghost" data-role="remove" onClick={onRemove} aria-label={`Remove rule ${index + 1}`}>Remove</button>
        </span>
      </div>

      {messages.length > 0 && (
        <ul class="rule-errors" id={errorId} role="alert">
          {messages.map(([part, message]) => <li key={part}>{part === "row" ? message : `${part}: ${message}`}</li>)}
        </ul>
      )}
    </li>
  );
}
