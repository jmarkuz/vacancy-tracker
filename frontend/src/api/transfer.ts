import { requestJson } from "./client";
import type { Vacancy } from "./types";

/** One rejected row of an import (ImportReport on the server). */
export interface ImportReport {
  total: number;
  imported: number;
  errors: { row: number; field: string; message: string }[];
}

/** CSV or JSON backup; the type is taken from the file name. */
export function importFile(file: File): Promise<ImportReport> {
  const csv = file.name.toLowerCase().endsWith(".csv");
  return requestJson<ImportReport>("POST", "/api/import/vacancies", file, csv ? "text/csv" : "application/json");
}

/** Reads the page's JobPosting data (T-023); a page without it answers 422. */
export function importFromUrl(url: string): Promise<Vacancy> {
  return requestJson<Vacancy>("POST", "/api/vacancies/import-url", { url });
}

export function createVacancy(input: { title: string; company: string; url?: string; description?: string }): Promise<Vacancy> {
  return requestJson<Vacancy>("POST", "/api/vacancies", input);
}

export const EXPORTS = [
  { href: "/api/export/vacancies.csv", label: "CSV", hint: "UTF-8 with BOM, opens in Excel" },
  { href: "/api/export/vacancies.xlsx", label: "XLSX", hint: "Excel workbook" },
  { href: "/api/export/backup.json", label: "JSON backup", hint: "everything, including profiles and history" },
];
