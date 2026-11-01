import { getJson } from "./client";
import type { Criterion } from "../domain/criteria";

export interface SavedView {
  id: string;
  name: string;
  criteria: Criterion[];
  sort: string | null;
  visibleColumns: string[];
}

export interface ViewInput {
  name: string;
  criteria: Criterion[];
  sort: string | null;
  visibleColumns: string[];
}

export const listViews = (signal?: AbortSignal) => getJson<SavedView[]>("/api/views", signal);

export const createView = (input: ViewInput) => send<SavedView>("POST", "/api/views", input);

export const updateView = (id: string, input: ViewInput) => send<SavedView>("PUT", `/api/views/${id}`, input);

export async function deleteView(id: string): Promise<void> {
  await send<void>("DELETE", `/api/views/${id}`);
}

async function send<T>(method: string, url: string, body?: unknown): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json", Accept: "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const problem = (await response.json()) as { detail?: string; title?: string };
      message = problem.detail ?? problem.title ?? message;
    } catch {
      /* keep generic message */
    }
    throw new Error(message);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}
