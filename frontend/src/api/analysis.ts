import { getJson, requestJson } from "./client";
import type { ResumeFit } from "./resume";

export type Priority = "must" | "important" | "nice";
export type MatchStatus = "covered" | "partial" | "missing";

export interface Requirement {
  id: string;
  text: string;
  priority: Priority;
  terms: string[];
}

/** The employer's requirements, built once per vacancy text by AI (Checklist on the server). */
export interface Checklist {
  coreProblem: string;
  requirements: Requirement[];
}

/** A requirement with the status the server checked; quote is the document's own words; note says why it was lowered. */
export interface MatchItem {
  requirementId: string;
  text: string;
  priority: Priority;
  status: MatchStatus;
  quote: string | null;
  note: string | null;
}

/** How well one document covers the checklist; the score is computed by code (T-095). */
export interface EmployerMatch {
  score: number;
  items: MatchItem[];
}

export interface GoalParameter {
  name: string;
  weight: number;
  share: number;
  score: number;
  explanation: string;
}

/** How well the vacancy fits the preset's goal; the deal-breaker is apart from the score. */
export interface GoalMatch {
  score: number;
  parameters: GoalParameter[];
  dealBreaker: boolean;
  dealBreakerReason: string | null;
}

export interface DocumentScore {
  source: "base" | "version";
  documentId: string | null;
  version: number | null;
  match: EmployerMatch;
}

export type AnalysisState = "none" | "current" | "stale" | "not_ready";

/** AnalysisService.Analysis: what the drawer shows. */
export interface Analysis {
  aiEnabled: boolean;
  quickFit: ResumeFit | null;
  checklistState: AnalysisState;
  checklist: Checklist | null;
  goalState: AnalysisState;
  goal: GoalMatch | null;
  baseResume: DocumentScore | null;
  baseCoverLetter: DocumentScore | null;
  resume: DocumentScore | null;
  coverLetter: DocumentScore | null;
}

const url = (vacancyId: string, presetId: string | null) =>
  `/api/vacancies/${encodeURIComponent(vacancyId)}/analysis${presetId ? `?presetId=${encodeURIComponent(presetId)}` : ""}`;

/** What is known without calling AI. */
export const getAnalysis = (vacancyId: string, presetId: string | null, signal?: AbortSignal) => getJson<Analysis>(url(vacancyId, presetId), signal);

/** Computes what is missing with AI (when it is on); cached on the server. */
export const runAnalysis = (vacancyId: string, presetId: string | null) => requestJson<Analysis>("POST", url(vacancyId, presetId));

/** The Employer match of a text not saved yet. */
export const matchText = (vacancyId: string, text: string) =>
  requestJson<EmployerMatch>("POST", `/api/vacancies/${encodeURIComponent(vacancyId)}/match`, { text });

export const PRIORITY_LABEL: Record<Priority, string> = { must: "Must-have", important: "Important", nice: "Nice-to-have" };
export const STATUS_LABEL: Record<MatchStatus, string> = { covered: "Covered", partial: "Partial", missing: "Missing" };
