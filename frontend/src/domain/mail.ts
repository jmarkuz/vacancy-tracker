import type { SyncResult } from "../api/mail";

/** The line shown when a read of the mailbox is done, for example "Read 12 mails: 3 about jobs, 2 linked, 1 suggestion." */
export function syncSummary(r: SyncResult): string {
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  return `Read ${plural(r.seen, "mail", "mails")}: ${r.stored} about jobs, ${r.linked} linked, ${plural(r.suggested, "suggestion", "suggestions")}.`;
}

/** How often the screen asks whether a running read has finished. */
export const SYNC_POLL_MS = 1500;
