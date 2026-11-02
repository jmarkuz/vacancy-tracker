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
}

export interface MailStatus {
  configured: boolean;
  folders: string[];
  pending: number;
  lastSync: string | null;
  lastError: string | null;
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

export const importEml = (file: File) =>
  requestJson<{ stored: boolean; reason: string; correspondence: Mail | null }>("POST", "/api/mail/import", file, "message/rfc822");

export const acceptSuggestion = (id: string) =>
  requestJson<{ vacancyId: string; vacancyStatus: string; mail: Mail }>("POST", `/api/mail/${encodeURIComponent(id)}/accept`);

export const dismissSuggestion = (id: string) => requestJson<Mail>("POST", `/api/mail/${encodeURIComponent(id)}/dismiss`);

export const relinkMail = (id: string, vacancyId: string | null) =>
  requestJson<Mail>("PUT", `/api/mail/${encodeURIComponent(id)}/vacancy`, { vacancyId });

/** "Move to Interview?" for a pending suggestion; null when there is nothing to decide. */
export function suggestionText(m: Mail): string | null {
  return m.suggestionState === "pending" && m.suggestedStatus ? `Move to ${m.suggestedStatus}?` : null;
}
