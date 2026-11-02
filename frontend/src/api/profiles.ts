import { ApiError, getJson } from "./client";

export interface Thresholds {
  inboxMinScore: number;
  notifyMinScore: number;
  maxAgeDays: number;
}

export interface Criterion {
  id: string;
  field: string;
  operator: string;
  value?: unknown;
  mode: string;
  weight: number;
  missingValuePolicy: string;
  label?: string | null;
}

/** The editable content of a profile: what a request sends and a version snapshot stores. */
export interface ProfileInput {
  name: string;
  enabled: boolean;
  sources: string[];
  scheduleOverride: string | null;
  thresholds: Thresholds;
  criteria: Criterion[];
}

export interface SearchProfile extends ProfileInput {
  id: string;
  version: number;
  updatedAt: string;
}

export type ValueShape =
  | "NONE"
  | "LIST"
  | "TEXT"
  | "PATTERN"
  | "NUMBER"
  | "RANGE"
  | "BOOLEAN"
  | "SALARY"
  | "SALARY_RANGE";

/** What the rule builder may offer, served by GET /api/profiles/meta. */
export interface Meta {
  fields: { name: string; kind: string; operators: { name: string; valueShape: ValueShape }[] }[];
  modes: string[];
  missingValuePolicies: string[];
  minWeight: number;
  maxWeight: number;
  defaultThresholds: Thresholds;
}

export interface ValidationResult {
  valid: boolean;
  errors: Record<string, string>;
}

/** A save the server rejected, with its errors by path (for example criteria[1].operator). */
export class ProfileRejected extends Error {
  constructor(
    readonly status: number,
    readonly errors: Record<string, string>,
    message: string,
  ) {
    super(message);
  }
}

export const listProfiles = (signal?: AbortSignal) => getJson<SearchProfile[]>("/api/profiles", signal);
export const getMeta = (signal?: AbortSignal) => getJson<Meta>("/api/profiles/meta", signal);

export async function validateProfile(input: ProfileInput, signal?: AbortSignal): Promise<ValidationResult> {
  const response = await fetch("/api/profiles/validate", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(input),
    signal,
  });
  if (!response.ok) {
    throw new ApiError(response.status, `Validation failed (${response.status})`);
  }
  return (await response.json()) as ValidationResult;
}

export const createProfile = (input: ProfileInput) => save<SearchProfile>("POST", "/api/profiles", input);
export const updateProfile = (id: string, input: ProfileInput) => save<SearchProfile>("PUT", `/api/profiles/${id}`, input);

export async function deleteProfile(id: string): Promise<void> {
  const response = await fetch(`/api/profiles/${id}`, { method: "DELETE" });
  if (!response.ok) {
    throw new ApiError(response.status, `Could not delete the profile (${response.status})`);
  }
}

async function save<T>(method: string, url: string, body: ProfileInput): Promise<T> {
  const response = await fetch(url, {
    method,
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(body),
  });
  if (response.ok) {
    return (await response.json()) as T;
  }
  let errors: Record<string, string> = {};
  let message = `Could not save the profile (${response.status})`;
  try {
    const problem = (await response.json()) as { errors?: Record<string, string>; detail?: string };
    errors = problem.errors ?? {};
    message = problem.detail ?? message;
  } catch {
    /* the body was not JSON; keep the generic message */
  }
  throw new ProfileRejected(response.status, errors, message);
}

export interface MatchReason {
  criterionId: string;
  delta: number;
  kind: string;
  text: string;
}

export interface PreviewSample {
  vacancyId: string;
  title: string;
  company: string;
  kind: "inbox" | "below-threshold" | "filtered-out";
  score: number | null;
  reasons: MatchReason[];
}

export interface Preview {
  total: number;
  applicable: number;
  matched: number;
  inbox: number;
  filteredOut: number;
  samples: PreviewSample[];
}

/** Dry run against the stored vacancies; nothing is written. */
/** profileId is the stored profile being edited, so its own company lists count; omit it for a new profile. */
export async function previewProfile(input: ProfileInput, signal?: AbortSignal, profileId?: string): Promise<Preview> {
  const query = profileId ? `?profileId=${encodeURIComponent(profileId)}` : "";
  const response = await fetch(`/api/profiles/preview${query}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(input),
    signal,
  });
  if (!response.ok) {
    throw new ApiError(response.status, `Preview failed (${response.status})`);
  }
  return (await response.json()) as Preview;
}

export interface ImportResult {
  profile: SearchProfile;
  created: boolean;
}

/** Sends a YAML or JSON profile file as text. A rejected file throws ProfileRejected with the errors by path. */
export async function importProfile(text: string): Promise<ImportResult> {
  const response = await fetch("/api/profiles/import", {
    method: "POST",
    headers: { "Content-Type": "text/plain", Accept: "application/json" },
    body: text,
  });
  if (response.ok) {
    return (await response.json()) as ImportResult;
  }
  let errors: Record<string, string> = {};
  try {
    errors = ((await response.json()) as { errors?: Record<string, string> }).errors ?? {};
  } catch {
    // Not a JSON error body.
  }
  throw new ProfileRejected(response.status, errors, "The file was not imported");
}

export const exportUrl = (id: string, format: "yaml" | "json") => `/api/profiles/${id}/export?format=${format}`;
