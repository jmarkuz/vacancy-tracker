import { getJson, requestJson } from "./client";

/** A stored job email (Correspondence on the server). */
export interface Mail {
  id: string;
  mailKey: string;
  folder: string | null;
  sender: string | null;
  senderName: string | null;
  subject: string | null;
  receivedAt: string | null;
  snippet: string | null;
  category: string;
  vacancyId: string | null;
  linkReason: string | null;
  suggestedStatus: string | null;
  suggestionState: "none" | "pending" | "accepted" | "dismissed";
  createdAt: string;
  /** The vacancies listed in a digest mail (a job alert with several postings); empty for any other mail. */
  jobs: DigestJob[];
}

/** One posting read out of a digest mail. company, location and url are null when the mail does not show them. */
export interface DigestJob {
  title: string;
  company: string | null;
  location: string | null;
  url: string | null;
}

export interface MailStatus {
  configured: boolean;
  folders: string[];
  pending: number;
  lastSync: string | null;
  lastError: string | null;
  /** True while a read of the mailbox runs (started here or by the schedule). */
  syncing: boolean;
  /** What the last read did; null before the first one. */
  lastResult: SyncResult | null;
}

export interface SyncResult {
  configured: boolean;
  seen: number;
  stored: number;
  linked: number;
  suggested: number;
  error: string | null;
}

export const CATEGORY_LABEL: Record<string, string> = {
  rejection: "Rejection",
  offer: "Offer",
  test_task: "Test task",
  interview: "Interview",
  application_viewed: "Application viewed",
  application_received: "Application received",
  recruiter_message: "Recruiter message",
  other_job: "Job mail",
};

/** A digest (two or more postings) is a job alert, whatever the server called it. */
export const isDigest = (m: Mail): boolean => (m.jobs?.length ?? 0) >= 2;

export const categoryLabel = (m: Mail): string => (isDigest(m) ? "Job alert" : (CATEGORY_LABEL[m.category] ?? m.category));

export const getMailStatus = (signal?: AbortSignal) => getJson<MailStatus>("/api/mail/status", signal);

export const listMail = (opts: { vacancyId?: string; pending?: boolean } = {}, signal?: AbortSignal) => {
  const query = new URLSearchParams();
  if (opts.vacancyId) {
    query.set("vacancyId", opts.vacancyId);
  }
  if (opts.pending) {
    query.set("pending", "true");
  }
  return getJson<Mail[]>(`/api/mail${query.toString() ? `?${query}` : ""}`, signal);
};

export const syncMail = () => requestJson<SyncResult>("POST", "/api/mail/sync");

export interface SyncStart {
  started: boolean;
  reason: string | null;
}

/** Starts reading the mailbox in the background and answers at once; the status says when it is done. */
export const startMailSync = () => requestJson<SyncStart>("POST", "/api/mail/sync/start");

/**
 * Forgets which mails were handled and reads the mailbox again in the background. Stored mail is kept and gets
 * what newer versions read from it (the vacancies of a digest); a mail you deleted comes back.
 */
export const startMailReread = () => requestJson<SyncStart>("POST", "/api/mail/reread");

export const importEml = (file: File) =>
  requestJson<{ stored: boolean; reason: string; correspondence: Mail | null }>("POST", "/api/mail/import", file, "message/rfc822");

export const acceptSuggestion = (id: string) =>
  requestJson<{ vacancyId: string; vacancyStatus: string; mail: Mail }>("POST", `/api/mail/${encodeURIComponent(id)}/accept`);

export const dismissSuggestion = (id: string) => requestJson<Mail>("POST", `/api/mail/${encodeURIComponent(id)}/dismiss`);

export const relinkMail = (id: string, vacancyId: string | null) =>
  requestJson<Mail>("PUT", `/api/mail/${encodeURIComponent(id)}/vacancy`, { vacancyId });

/** Deletes stored mails from the app. The mailbox itself is not touched. */
export const deleteMail = (ids: string[]) => requestJson<{ deleted: number }>("POST", "/api/mail/delete", { ids });

/** Deletes the stored mails older than this many days. */
export const deleteOldMail = (days: number) => requestJson<{ deleted: number }>("POST", "/api/mail/delete-old", { days });

/** "Move to Interview?" for a pending suggestion; null when there is nothing to decide. */
export function suggestionText(m: Mail): string | null {
  return m.suggestionState === "pending" && m.suggestedStatus ? `Move to ${m.suggestedStatus}?` : null;
}

/** An application a mail can be linked to by hand. */
export interface ApplicationOption {
  id: string;
  title: string;
  company: string | null;
  status: string;
}

export const listApplications = (signal?: AbortSignal) => getJson<ApplicationOption[]>("/api/mail/applications", signal);
