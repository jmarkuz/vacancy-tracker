import { ApiError, getJson } from "./client";

export interface AuthStatus {
  /** False in the local profile, which has no token. */
  required: boolean;
  authenticated: boolean;
}

export const getAuthStatus = (signal?: AbortSignal) => getJson<AuthStatus>("/api/auth/status", signal);

/** Resolves when the token was accepted and the cookie is set. Rejects with an ApiError: 401 wrong, 429 too many tries. */
export async function login(token: string): Promise<void> {
  const response = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ token }),
  });
  if (response.ok) {
    return;
  }
  throw new ApiError(response.status, response.status === 429 ? retryText(response.headers.get("Retry-After")) : "That token is not correct.");
}

export async function logout(): Promise<void> {
  await fetch("/api/auth/logout", { method: "POST" });
}

/** "Too many wrong tokens. Try again in 9 minutes." from a Retry-After value in seconds. */
export function retryText(retryAfter: string | null): string {
  const seconds = Number(retryAfter);
  if (!Number.isFinite(seconds) || seconds <= 0) {
    return "Too many wrong tokens. Try again later.";
  }
  const minutes = Math.ceil(seconds / 60);
  return `Too many wrong tokens. Try again in ${minutes} ${minutes === 1 ? "minute" : "minutes"}.`;
}

export const UNAUTHORIZED_EVENT = "vt-unauthorized";

/**
 * Tells the app when the API answers 401 (the cookie expired or was cleared), so it can show the login page.
 * Calls to the auth endpoints are left out: a wrong token at login is not a lost session.
 */
export function watchUnauthorized(target: Window = window): void {
  const original = target.fetch.bind(target);
  target.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const response = await original(input, init);
    const url = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
    if (response.status === 401 && url.includes("/api/") && !url.includes("/api/auth/")) {
      target.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
    }
    return response;
  };
}

/** True for the address of the local profile, which has no token: only there may a failed status check be taken as "open". */
export function isLocalHost(hostname: string): boolean {
  return hostname === "localhost" || hostname === "127.0.0.1" || hostname === "[::1]" || hostname === "::1";
}
