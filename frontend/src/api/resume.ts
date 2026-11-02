import { ApiError, getJson, requestJson } from "./client";

/** The active resume (ResumeController.ResumeResponse). */
export interface Resume {
  id: string;
  name: string;
  fileName: string | null;
  text: string;
  keywords: string[];
  revision: number;
  uploadedAt: string;
  updatedAt: string;
  /** Estimated from the date ranges in the text; null when it shows none. */
  years: number | null;
  hasOriginal: boolean;
}

export interface FitReason {
  /** matched, missing, title, years or thin */
  kind: string;
  text: string;
}

/** How well a vacancy fits the resume (T-081): 0..100 with the reasons behind it. */
export interface ResumeFit {
  vacancyId: string;
  resumeId: string;
  resumeRevision: number;
  score: number;
  matched: string[];
  missing: string[];
  reasons: FitReason[];
  computedAt: string;
}

/** The resume, or null when none has been uploaded yet. */
export async function getResume(signal?: AbortSignal): Promise<Resume | null> {
  try {
    return await getJson<Resume>("/api/resume", signal);
  } catch (e: unknown) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}

export const uploadResume = (file: File) =>
  requestJson<Resume>("POST", `/api/resume/file?fileName=${encodeURIComponent(file.name)}`, file, file.type || "application/octet-stream");

export const pasteResume = (name: string, text: string) => requestJson<Resume>("POST", "/api/resume/text", { name, text });

export const saveKeywords = (keywords: string[]) => requestJson<Resume>("PUT", "/api/resume/keywords", { keywords });

export const deleteResume = () => requestJson<void>("DELETE", "/api/resume");

/** Fits of the listed vacancies; an empty list when there is no resume. At most 200 ids per call. */
export function getFits(ids: string[], signal?: AbortSignal): Promise<ResumeFit[]> {
  if (ids.length === 0) {
    return Promise.resolve([]);
  }
  return getJson<ResumeFit[]>(`/api/resume/fits?ids=${ids.slice(0, 200).join(",")}`, signal);
}

/** The fit of one vacancy, or null when there is no resume. */
export async function getFit(id: string, signal?: AbortSignal): Promise<ResumeFit | null> {
  try {
    return await getJson<ResumeFit>(`/api/vacancies/${encodeURIComponent(id)}/fit`, signal);
  } catch (e: unknown) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}
