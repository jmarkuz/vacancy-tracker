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

/** The server cuts a long text at its storage limit and ends it with this mark. */
const CUT_MARK = "…";
/** Shown under an unfolded text that was cut when it was stored. */
export const MAIL_REST_NOTE = "The rest is in your mailbox.";

/** Whether the stored text is only the start of the mail (the server ends a cut text with "…"). */
export const textWasCut = (text: string | null): boolean => (text ?? "").trimEnd().endsWith(CUT_MARK);

/** How long a delete waits, with Undo on offer, before it is really sent. */
export const DELETE_UNDO_MS = 8000;

const mails = (n: number) => `${n} mail${n === 1 ? "" : "s"}`;

/** The ids of the listed mails received more than `days` days before `now`. Mails without a date are never counted. */
export function olderThan(items: ReadonlyArray<{ id: string; receivedAt: string | null }>, days: number, now: number): string[] {
  const limit = now - days * 86_400_000;
  return items.filter((m) => m.receivedAt !== null && Date.parse(m.receivedAt) < limit).map((m) => m.id);
}

/** What the confirmation says before a delete: the title, the explanation and the button. */
export function deleteConfirmText(count: number, olderDays: number | null): { title: string; body: string; action: string } {
  const safe = "Your mailbox is not touched. For a few seconds you can still undo it.";
  if (olderDays === null) {
    return { title: `Delete ${mails(count)}?`, body: `${count === 1 ? "It is" : "They are"} removed from this app. ${safe}`, action: "Delete" };
  }
  const body =
    count > 0
      ? `${mails(count)} in the list ${count === 1 ? "is" : "are"} older than ${olderDays} days; every stored mail that old is removed from this app. ${safe}`
      : `Every stored mail older than ${olderDays} days is removed from this app. ${safe}`;
  return { title: `Delete mails older than ${olderDays} days?`, body, action: "Delete old" };
}

/** The toast while a delete waits for its Undo. */
export const deletingText = (count: number, olderDays: number | null): string =>
  olderDays === null ? `Deleting ${mails(count)}…` : count > 0 ? `Deleting ${mails(count)} older than ${olderDays} days…` : `Deleting mails older than ${olderDays} days…`;
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

/** Label under the slider: the bare number of minutes ("5", "60"); the slider's own label says "minutes". */
export const intervalTick = (minutes: number): string => String(minutes);

/** "10:45 AM" (in the viewer's locale) for the next check. */
export function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}
