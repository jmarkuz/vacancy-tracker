import type { Preview, PreviewSample } from "../api/profiles";

const n = (value: number) => value.toLocaleString("en-US");

/** The sentence from docs/search-profiles.md section 9. */
export function previewSummary(p: Preview): string {
  if (p.total === 0) {
    return "There are no vacancies yet, so there is nothing to preview.";
  }
  const scope = p.applicable === p.total ? "" : ` (${n(p.applicable)} come from this profile's sources)`;
  return `Of ${n(p.total)} existing vacancies${scope}, ${n(p.matched)} would match, ${n(p.inbox)} of them would land in the Inbox. ${n(p.filteredOut)} would be filtered out.`;
}

export function sampleLabel(s: PreviewSample): string {
  switch (s.kind) {
    case "inbox":
      return `Inbox, score ${s.score}`;
    case "below-threshold":
      return `Below the Inbox threshold, score ${s.score}`;
    default:
      return "Filtered out";
  }
}
