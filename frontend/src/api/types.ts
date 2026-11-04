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
  /** When the source says the posting was published; null when it does not say. */
  postedAt?: string | null;
  /** The "likely ghost" advice: how many signs, and why (posted long ago, listed long, no salary). */
  ghost?: Ghost;
  isNew: boolean;
  /** From the posting text: true offered, false not offered, null not stated (ADR-044). */
  visaSponsorship?: boolean | null;
}

export interface Ghost {
  likely: boolean;
  signs: number;
  reasons: string[];
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
