import { ApiError, getJson } from "./client";

export interface RecalculationProgress {
  jobId: string;
  state: "RUNNING" | "COMPLETED";
  processed: number;
  total: number;
  failed: number;
  startedAt: string;
  finishedAt: string | null;
}

/** Starts a recalculation, or queues one more pass when a job is already running. */
export async function startRecalculation(): Promise<{ jobId: string; started: boolean }> {
  const response = await fetch("/api/recalculation", { method: "POST", headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`Could not start the recalculation (${response.status})`);
  }
  return (await response.json()) as { jobId: string; started: boolean };
}

/** Progress of the running job or the last one; null before the first job has run. */
export async function getRecalculationProgress(): Promise<RecalculationProgress | null> {
  try {
    return await getJson<RecalculationProgress>("/api/recalculation");
  } catch (e: unknown) {
    if (e instanceof ApiError && e.status === 404) {
      return null;
    }
    throw e;
  }
}
