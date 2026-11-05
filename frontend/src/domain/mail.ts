import type { SyncResult } from "../api/mail";

/** The line shown when a read of the mailbox is done, for example "Read 12 mails: 3 about jobs, 2 linked, 1 suggestion." */
export function syncSummary(r: SyncResult): string {
  const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;
  return `Read ${plural(r.seen, "mail", "mails")}: ${r.stored} about jobs, ${r.linked} linked, ${plural(r.suggested, "suggestion", "suggestions")}.`;
}

/** How often the screen asks whether a running read has finished. */
export const SYNC_POLL_MS = 1500;

/** "Company · Place" for a posting, leaving out what the mail does not say. */
export function jobMeta(job: { company: string | null; location: string | null }): string {
  return [job.company, job.location].filter((part): part is string => !!part && part.trim().length > 0).join(" · ");
}

/** "3 vacancies in this mail". */
export const digestHeading = (count: number): string => `${count} ${count === 1 ? "vacancy" : "vacancies"} in this mail`;

/** A link from a mail is only followed when it is a plain web address. */
export const safeHref = (url: string | null): string | null => (url && /^https?:\/\//i.test(url) ? url : null);

/** The paragraphs of a mail's text (one per line), and whether it is long enough to fold. */
export function bodyParagraphs(text: string | null): { paragraphs: string[]; long: boolean } {
  const paragraphs = (text ?? "").split(/\n+/).map((p) => p.trim()).filter((p) => p.length > 0);
  return { paragraphs, long: paragraphs.length > 4 || (text ?? "").length > 320 };
}

/** What the owner is asked before a re-read starts. */
export const REREAD_CONFIRM =
  "Read your mailbox again? Mails already here are kept and get their vacancy lists; mails you deleted come back. " +
  "Your mailbox itself is not changed.";

/** The steps of the "how often" slider, in minutes: the slider moves over their indexes. */
export const SYNC_INTERVAL_STEPS = [5, 10, 15, 30, 45, 60] as const;

/** The default of the server, shown until the status says otherwise. */
export const DEFAULT_SYNC_MINUTES = 15;

/** Index of a number of minutes among the steps; an unknown value goes to the nearest step. */
export function intervalStepIndex(minutes: number): number {
  let best = 0;
  SYNC_INTERVAL_STEPS.forEach((m, i) => {
    if (Math.abs(m - minutes) < Math.abs(SYNC_INTERVAL_STEPS[best] - minutes)) {
      best = i;
    }
  });
  return best;
}

/** "every 15 minutes", "every hour". */
export const intervalPhrase = (minutes: number): string => (minutes === 60 ? "every hour" : `every ${minutes} minutes`);

/** Label under the slider: "5 min", "1 h". */
export const intervalTick = (minutes: number): string => (minutes === 60 ? "1 h" : `${minutes} min`);

/** "10:45 AM" (in the viewer's locale) for the next check. */
export function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}
