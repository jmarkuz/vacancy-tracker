import { getJson } from "./client";

export interface LatestRun {
  runId: string | null;
  status: string | null;
  finishedAt: string | null;
  newCount: number;
  updated: number;
  failedSources: number;
  nextScheduledAt: string;
}

export interface StartedRun {
  runId: string;
  started: boolean;
  status: string;
}

export interface SourceEvent {
  adapter: string;
  state: "running" | "finished" | "failed";
  new?: number;
  updated?: number;
  duplicates?: number;
  errors?: number;
  error?: string;
}

export interface DoneEvent {
  status: string;
  new: number;
  updated: number;
  failedSources: number;
}

export const getLatestRun = (signal?: AbortSignal) => getJson<LatestRun>("/api/runs/latest", signal);

/** Starts a run, or joins the active one (the server answers 200 for an active run, 202 for a new one). */
export async function startRun(): Promise<StartedRun> {
  const response = await fetch("/api/runs", { method: "POST", headers: { Accept: "application/json" } });
  if (!response.ok) {
    throw new Error(`Could not start a refresh (${response.status})`);
  }
  return (await response.json()) as StartedRun;
}

export interface RunStream {
  close(): void;
}

/** Live progress for one run. The stream replays earlier events, so a late open still shows all of them. */
export function openRunStream(
  runId: string,
  handlers: { onSource: (e: SourceEvent) => void; onDone: (e: DoneEvent) => void; onError: () => void },
): RunStream {
  const source = new EventSource(`/api/runs/${encodeURIComponent(runId)}/events`);
  source.addEventListener("source", (ev) => handlers.onSource(JSON.parse((ev as MessageEvent).data) as SourceEvent));
  source.addEventListener("done", (ev) => {
    const data = JSON.parse((ev as MessageEvent).data) as DoneEvent;
    source.close();
    handlers.onDone(data);
  });
  source.onerror = () => {
    source.close();
    handlers.onError();
  };
  return { close: () => source.close() };
}
