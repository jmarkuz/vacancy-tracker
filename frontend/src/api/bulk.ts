import { ApiError } from "./client";

export interface Changed {
  id: string;
  from: string;
  wasNew: boolean;
}

export interface Skipped {
  id: string;
  reason: string;
}

export interface BulkResult {
  changed: Changed[];
  skipped: Skipped[];
}

export interface UndoItem {
  id: string;
  /** The status the vacancy had before the batch. */
  status: string;
  /** The status the batch set. Undo only applies while the vacancy is still in it. */
  expected: string;
  wasNew: boolean;
}

async function post(url: string, body: unknown): Promise<BulkResult> {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const problem = (await response.json()) as { detail?: string; title?: string };
      message = problem.detail || problem.title || message;
    } catch {
      // Keep the generic message.
    }
    throw new ApiError(response.status, message);
  }
  return (await response.json()) as BulkResult;
}

export const bulkStatus = (ids: string[], status: string) => post("/api/bulk/vacancies/status", { ids, status });
export const bulkUndo = (items: UndoItem[]) => post("/api/bulk/vacancies/undo", { items });
