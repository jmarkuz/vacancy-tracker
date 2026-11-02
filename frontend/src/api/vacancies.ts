import { ApiError, getJson } from "./client";
import type { Vacancy } from "./types";

export interface StatusEvent {
  from: string | null;
  to: string;
  at: string;
  note: string | null;
}

/** Only the fields the drawer edits. A null field is left unchanged on the server. */
export interface VacancyPatch {
  description?: string;
  notes?: string;
  contact?: string;
  priority?: number;
  nextActionAt?: string;
  nextActionNote?: string;
}

export const getVacancy = (id: string, signal?: AbortSignal) =>
  getJson<Vacancy>(`/api/vacancies/${encodeURIComponent(id)}`, signal);

export const getHistory = (id: string, signal?: AbortSignal) =>
  getJson<StatusEvent[]>(`/api/vacancies/${encodeURIComponent(id)}/history`, signal);

export async function patchVacancy(id: string, patch: VacancyPatch): Promise<Vacancy> {
  return sendJson<Vacancy>("PATCH", `/api/vacancies/${encodeURIComponent(id)}`, patch);
}

export async function changeStatus(id: string, status: string, note?: string): Promise<Vacancy> {
  return sendJson<Vacancy>("POST", `/api/vacancies/${encodeURIComponent(id)}/status`, { status, note });
}

async function sendJson<T>(method: string, url: string, body: unknown): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const problem = (await response.json()) as { detail?: string; title?: string; errors?: Record<string, string> };
      const fields = problem.errors ? Object.values(problem.errors).join("; ") : "";
      message = fields || problem.detail || problem.title || message;
    } catch {
      /* keep generic message */
    }
    throw new ApiError(response.status, message);
  }
  return (await response.json()) as T;
}
