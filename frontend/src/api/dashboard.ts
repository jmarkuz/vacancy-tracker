import { getJson } from "./client";

export interface TopMatch {
  id: string;
  title: string;
  company: string;
  score: number;
}

export interface NextAction {
  id: string;
  title: string;
  company: string;
  status: string;
  dueAt: string;
  note: string | null;
  overdue: boolean;
}

export interface FunnelStage {
  status: string;
  count: number;
}

export interface Week {
  start: string;
  applications: number;
}

export interface Dashboard {
  newToday: number;
  inbox: number;
  active: number;
  interviews: number;
  overdue: number;
  topMatches: TopMatch[];
  funnel: FunnelStage[];
  applicationsPerWeek: Week[];
  nextActions: NextAction[];
}

export const getDashboard = (signal?: AbortSignal) => getJson<Dashboard>("/api/dashboard", signal);
