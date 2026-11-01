/**
 * Mirror of the backend transition table (VacancyStatus, ADR-023). The server enforces it;
 * the UI uses it only to offer valid choices, so keep the two in sync.
 */
export const STATUS_TRANSITIONS: Record<string, string[]> = {
  New: ["Saved", "Applied", "Dismissed", "Hidden", "Expired"],
  Saved: ["Applied", "Dismissed", "Withdrawn", "Expired"],
  Applied: ["Screening", "Rejected", "Withdrawn"],
  Screening: ["Interview", "Test task", "Rejected", "Withdrawn"],
  Interview: ["Test task", "Offer", "Rejected", "Withdrawn"],
  "Test task": ["Interview", "Offer", "Rejected", "Withdrawn"],
  Offer: ["Accepted", "Rejected", "Withdrawn"],
  Accepted: [],
  Rejected: ["New"],
  Withdrawn: ["New"],
  Expired: ["New"],
  Dismissed: ["New"],
  Hidden: ["New"],
};

export function allowedNext(status: string): string[] {
  return STATUS_TRANSITIONS[status] ?? [];
}
