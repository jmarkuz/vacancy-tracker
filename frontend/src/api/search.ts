import { getJson } from "./client";

export interface SearchHit {
  id: string;
  title: string;
  company: string;
  status: string;
  rank: number;
  /** Matched words are wrapped in [[ and ]]. */
  snippet: string;
}

export const searchVacancies = (q: string, signal?: AbortSignal) =>
  getJson<SearchHit[]>(`/api/search?q=${encodeURIComponent(q)}&limit=12`, signal);
