/** Summary line shown after a refresh, for example "+12 new, 3 updated, 1 source failed". */
export function summaryText(totals: { new: number; updated: number; failedSources: number }): string {
  const parts = [`+${totals.new} new`, `${totals.updated} updated`];
  if (totals.failedSources > 0) {
    parts.push(`${totals.failedSources} ${totals.failedSources === 1 ? "source" : "sources"} failed`);
  }
  return parts.join(", ");
}

/** "Refreshed 14:02 · next 18:00", or a plain next-run line when nothing has run yet. */
export function refreshedLine(lastFinishedAt: string | null, nextScheduledAt: string, locale?: string): string {
  const time = new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit" });
  const next = `next ${time.format(new Date(nextScheduledAt))}`;
  if (!lastFinishedAt) {
    return `Not refreshed yet · ${next}`;
  }
  return `Refreshed ${time.format(new Date(lastFinishedAt))} · ${next}`;
}
