import type { Criterion, Meta, ProfileInput } from "../api/profiles";
import { emptyProfile, parseList } from "./profileEditor";

/** What the first-run wizard asks. Nothing here has a default value: the user supplies every term. */
export interface WizardAnswers {
  name: string;
  keywords: string;
  workModes: string[];
  countries: string;
  salaryAmount: string;
  currency: string;
  period: "year" | "month";
}

export const emptyAnswers = (): WizardAnswers => ({
  name: "",
  keywords: "",
  workModes: [],
  countries: "",
  salaryAmount: "",
  currency: "",
  period: "year",
});

export function wizardErrors(a: WizardAnswers): Record<string, string> {
  const errors: Record<string, string> = {};
  if (a.name.trim() === "") {
    errors.name = "Give the profile a name";
  }
  if (parseList(a.keywords).length === 0) {
    errors.keywords = "Enter at least one word or phrase to look for";
  }
  if (a.salaryAmount.trim() !== "") {
    const amount = Number(a.salaryAmount);
    if (!Number.isFinite(amount) || amount <= 0) {
      errors.salaryAmount = "Enter a positive number";
    }
    if (!/^[A-Za-z]{3}$/.test(a.currency.trim())) {
      errors.currency = "Enter a three-letter currency code";
    }
  }
  return errors;
}

/**
 * The first profile from the answers. Every rule is "prefer", so the wizard never hides vacancies on its own;
 * the user can tighten a rule in the editor afterwards.
 */
export function wizardProfile(a: WizardAnswers, meta: Meta): ProfileInput {
  const criteria: Criterion[] = [];
  const add = (c: Omit<Criterion, "id" | "missingValuePolicy">) =>
    criteria.push({ ...c, id: `rule-${criteria.length + 1}`, missingValuePolicy: "ignore" });

  add({ field: "keywords", operator: "contains_any", value: parseList(a.keywords), mode: "prefer", weight: 8, label: "Looked-for words" });
  if (a.workModes.length > 0) {
    add({ field: "workMode", operator: "in", value: a.workModes, mode: "prefer", weight: 4, label: "Preferred work mode" });
  }
  const countries = parseList(a.countries).map((c) => c.toUpperCase());
  if (countries.length > 0) {
    add({ field: "country", operator: "in", value: countries, mode: "prefer", weight: 3, label: "Preferred country" });
  }
  if (a.salaryAmount.trim() !== "") {
    add({
      field: "salary",
      operator: "gte",
      value: { amount: Number(a.salaryAmount), currency: a.currency.trim().toUpperCase(), period: a.period },
      mode: "prefer",
      weight: 6,
      label: "Salary at least the minimum",
    });
  }
  return { ...emptyProfile(meta), name: a.name.trim(), criteria };
}
