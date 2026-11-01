import { ApiError, getJson } from "./client";
import { ProfileRejected } from "./profiles";

export type ListType = "allowlist" | "blocklist";

export interface CompanyListEntry {
  id: string;
  company: string;
  listType: ListType;
  /** Null for an entry that applies to every profile. */
  profileId: string | null;
}

export const listCompanyEntries = (signal?: AbortSignal) => getJson<CompanyListEntry[]>("/api/company-lists", signal);

export async function addCompanyEntry(company: string, listType: ListType, profileId: string | null): Promise<CompanyListEntry> {
  const response = await fetch("/api/company-lists", {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify({ company, listType, profileId }),
  });
  if (response.ok) {
    return (await response.json()) as CompanyListEntry;
  }
  let errors: Record<string, string> = {};
  try {
    errors = ((await response.json()) as { errors?: Record<string, string> }).errors ?? {};
  } catch {
    // Not a JSON error body.
  }
  throw new ProfileRejected(response.status, errors, "The company was not added");
}

export async function removeCompanyEntry(id: string): Promise<void> {
  const response = await fetch(`/api/company-lists/${id}`, { method: "DELETE" });
  if (!response.ok && response.status !== 404) {
    throw new ApiError(response.status, `Could not remove the entry (${response.status})`);
  }
}
