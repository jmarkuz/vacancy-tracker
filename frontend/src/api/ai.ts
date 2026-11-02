import { getJson, requestJson } from "./client";

/** AiService.Status: AI is used only when enabled (configured and switched on by the owner). */
export interface AiStatus {
  provider: string;
  configured: boolean;
  consented: boolean;
  enabled: boolean;
  consentedAt: string | null;
  notice: string;
}

export type DocumentKind = "resume" | "cover_letter";

/** A generated text not saved yet, with the base draft it adapts and the snapshot of what it was made from. */
export interface Draft {
  kind: DocumentKind;
  content: string;
  provider: string;
  model: string;
  snapshotId: string;
  baseDraft: string;
  presetName: string;
  /** Resume only: pages of its PDF, how many times AI shortened it, and why it still does not fit two pages. */
  pages: number | null;
  shortened: number;
  pageProblem: string | null;
}

export type DocumentOrigin = "ai" | "manual" | "base";

/** A saved version (TailoredDocument). */
export interface TailoredDocument {
  id: string;
  vacancyId: string;
  kind: DocumentKind;
  version: number;
  content: string;
  /** ai: adapted by AI and checked by you; manual: by hand; base: the preset's base draft as it is. */
  origin: DocumentOrigin;
  provider: string | null;
  model: string | null;
  createdAt: string;
  snapshotId: string | null;
  /** Pages of the stored PDF (resumes saved since T-097). */
  pdfPages: number | null;
}

export const getAiStatus = (signal?: AbortSignal) => getJson<AiStatus>("/api/ai/status", signal);
export const enableAi = () => requestJson<AiStatus>("POST", "/api/ai/consent");
export const disableAi = () => requestJson<AiStatus>("DELETE", "/api/ai/consent");

export const draftDocument = (vacancyId: string, kind: DocumentKind, presetId: string | null, instructions: string) =>
  requestJson<Draft>("POST", `/api/vacancies/${encodeURIComponent(vacancyId)}/documents/draft`, { kind, presetId, instructions });

/** Saves the preset's base draft as the next version, unchanged; works with AI off. */
export const saveAsIs = (vacancyId: string, kind: DocumentKind, presetId: string | null) =>
  requestJson<TailoredDocument>("POST", `/api/vacancies/${encodeURIComponent(vacancyId)}/documents/as-is`, { kind, presetId });

export const saveDocument = (
  vacancyId: string,
  doc: { kind: DocumentKind; content: string; origin: "ai" | "manual"; provider?: string; model?: string; snapshotId?: string | null },
) => requestJson<TailoredDocument>("POST", `/api/vacancies/${encodeURIComponent(vacancyId)}/documents`, doc);

export const listDocuments = (vacancyId: string, signal?: AbortSignal) =>
  getJson<TailoredDocument[]>(`/api/vacancies/${encodeURIComponent(vacancyId)}/documents`, signal);

/** AI shortens a resume that does not fit two pages, at most twice. */
export const shortenResume = (vacancyId: string, text: string) =>
  requestJson<{ content: string; pages: number; attempts: number }>("POST", `/api/vacancies/${encodeURIComponent(vacancyId)}/documents/shorten`, { text });

export const downloadUrl = (documentId: string, format: "pdf" | "docx" | "md" | "txt") =>
  `/api/documents/${encodeURIComponent(documentId)}/download?format=${format}`;

export const KIND_LABEL: Record<DocumentKind, string> = { resume: "Resume", cover_letter: "Cover letter" };

/** The latest saved version of each kind. */
export function latestByKind(docs: TailoredDocument[]): Partial<Record<DocumentKind, TailoredDocument>> {
  const out: Partial<Record<DocumentKind, TailoredDocument>> = {};
  for (const d of docs) {
    const current = out[d.kind];
    if (!current || d.version > current.version) {
      out[d.kind] = d;
    }
  }
  return out;
}

/** A plain starting point for a cover letter written by hand. */
export function coverLetterTemplate(title: string, company: string): string {
  return `Dear ${company} team,\n\nI am applying for the ${title} position.\n\n[Why this role and company]\n\n[Two or three results from your experience that match the vacancy]\n\nKind regards,\n`;
}

/** An application package (ApplicationPackage on the server). */
export interface ApplicationPackage {
  id: string;
  vacancyId: string;
  resumeDocumentId: string | null;
  coverLetterDocumentId: string | null;
  applyUrl: string | null;
  note: string | null;
  status: "prepared" | "submitted";
  createdAt: string;
  submittedAt: string | null;
  /** The package's own copy of the uploaded resume when no tailored version was chosen. */
  resumeFileName: string | null;
}

export const preparePackage = (
  vacancyId: string,
  body: { resumeDocumentId?: string | null; coverLetterDocumentId?: string | null; applyUrl?: string; note?: string },
) => requestJson<ApplicationPackage>("POST", `/api/vacancies/${encodeURIComponent(vacancyId)}/packages`, body);

export const listPackages = (vacancyId: string, signal?: AbortSignal) =>
  getJson<ApplicationPackage[]>(`/api/vacancies/${encodeURIComponent(vacancyId)}/packages`, signal);

export const packageUrl = (packageId: string) => `/api/packages/${encodeURIComponent(packageId)}/download`;

export const markSubmitted = (packageId: string, note?: string) =>
  requestJson<{ applicationPackage: ApplicationPackage; vacancyStatus: string }>(
    "POST",
    `/api/packages/${encodeURIComponent(packageId)}/submitted`,
    { note },
  );
