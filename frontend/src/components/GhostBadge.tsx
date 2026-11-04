import type { Ghost } from "../api/types";

/**
 * "Likely ghost": the posting may not have a real vacancy behind it. Shown only when the advice says likely, with the
 * reasons in words (never color alone, and never a verdict: the owner can disagree). Without the advice, nothing.
 */
export function GhostBadge({ ghost }: { ghost: Ghost | undefined }) {
  if (!ghost?.likely) {
    return null;
  }
  const why = ghost.reasons.join("; ");
  return (
    <span class="badge ghost-badge" title={why} aria-label={`Likely ghost: ${why}`}>
      Likely ghost
    </span>
  );
}

/** The reasons as a plain sentence for the detail views, or null when there are none to show. */
export function ghostReasons(ghost: Ghost | undefined): string | null {
  return ghost && ghost.likely && ghost.reasons.length > 0 ? ghost.reasons.join(" · ") : null;
}
