import type { ProblemDetail } from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** GET a JSON resource. Problem-detail bodies become readable ApiError messages. */
export async function getJson<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { headers: { Accept: "application/json" }, signal });
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    try {
      const problem = (await response.json()) as ProblemDetail;
      message = problem.detail ?? problem.title ?? message;
    } catch {
      /* body is not JSON; keep the generic message */
    }
    throw new ApiError(response.status, message);
  }
  return (await response.json()) as T;
}

/** Reads a problem-detail body into a readable message: field errors first, then detail or title. */
export async function problemMessage(response: Response): Promise<string> {
  let message = `Request failed (${response.status})`;
  try {
    const problem = (await response.json()) as { detail?: string; title?: string; errors?: Record<string, string> };
    const fields = problem.errors ? Object.values(problem.errors).join("; ") : "";
    message = fields || problem.detail || problem.title || message;
  } catch {
    /* body is not JSON; keep the generic message */
  }
  return message;
}

/**
 * Sends JSON (or a raw body such as a file) and reads JSON back. A 204 answer gives undefined.
 * Errors become an ApiError with the server's own message.
 */
export async function requestJson<T>(method: string, url: string, body?: unknown, contentType = "application/json"): Promise<T> {
  const raw = body instanceof Blob || typeof body === "string";
  const response = await fetch(url, {
    method,
    headers: body === undefined ? { Accept: "application/json" } : { "Content-Type": contentType, Accept: "application/json" },
    body: body === undefined ? undefined : raw ? (body as BodyInit) : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new ApiError(response.status, await problemMessage(response));
  }
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}
