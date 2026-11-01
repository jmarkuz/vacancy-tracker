import { getJson } from "./client";

export interface SearchProfile {
  id: string;
  name: string;
  enabled: boolean;
  sources: string[];
  version: number;
  updatedAt: string;
  criteria: unknown[];
}

export const listProfiles = (signal?: AbortSignal) => getJson<SearchProfile[]>("/api/profiles", signal);
