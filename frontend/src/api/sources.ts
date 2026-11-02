import { getJson } from "./client";

export type HealthState = "ok" | "warn" | "error" | "unknown";

export interface LastRun {
  fetched: number;
  newCount: number;
  updated: number;
  duplicates: number;
  errors: number;
  durationMs: number;
  errorMessage: string | null;
}

export interface SourceStatus {
  id: string;
  health: HealthState;
  lastSuccessAt: string | null;
  lastFailureAt: string | null;
  consecutiveFailures: number;
  circuitOpenUntil: string | null;
  lastError: string | null;
  lastRun: LastRun | null;
}

export const listSources = (signal?: AbortSignal) => getJson<SourceStatus[]>("/api/sources", signal);

/** Starts a run for one source. started is false when another run was already active. */
export async function refreshSource(id: string): Promise<{ runId: string; started: boolean }> {
  const response = await fetch(`/api/sources/${encodeURIComponent(id)}/refresh`, {
    method: "POST",
    headers: { Accept: "application/json" },
  });
  if (!response.ok) {
    throw new Error(`Could not refresh ${id} (${response.status})`);
  }
  return (await response.json()) as { runId: string; started: boolean };
}
