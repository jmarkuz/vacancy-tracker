/** Shapes returned by the backend (see VacancyController). Keep in sync with the Java records. */
export interface Vacancy {
  id: string;
  title: string;
  company: string;
  url: string | null;
  canonicalUrl: string | null;
  description: string | null;
  notes: string | null;
  contact: string | null;
  nextActionNote: string | null;
  userEditedFields: string[];
  location: string | null;
  country: string | null;
  workMode: string | null;
  status: string;
  priority: number | null;
  salaryMin: number | null;
  salaryMax: number | null;
  currency: string | null;
  salaryPeriod: string | null;
  tags: string[];
  nextActionAt: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  isNew: boolean;
}

export interface PageResponse<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface ProblemDetail {
  title?: string;
  detail?: string;
  errors?: Record<string, string>;
}
