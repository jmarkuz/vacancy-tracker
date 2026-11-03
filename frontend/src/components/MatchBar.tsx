import { level } from "../domain/match";

/**
 * A percentage as a bar (T-096). It is a meter for assistive technology and can be focused with Tab; the number and
 * the level are written out, so the colour only repeats what the text says.
 */
export function MatchBar({ label, score, note }: { label: string; score: number; note?: string }) {
  const lvl = level(score);
  return (
    <div class="match-bar">
      <div class="match-bar-head">
        <span>{label}</span>
        <span class="num">
          {score}% <span class="muted small">({lvl})</span>
        </span>
      </div>
      <div
        class={`match-track match-${lvl}`}
        role="meter"
        tabIndex={0}
        aria-label={label}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={score}
        aria-valuetext={`${score}% (${lvl})${note ? `, ${note}` : ""}`}
      >
        <div class="match-fill" style={{ width: `${Math.max(0, Math.min(100, score))}%` }} />
      </div>
      {note && <p class="muted small">{note}</p>}
    </div>
  );
}
