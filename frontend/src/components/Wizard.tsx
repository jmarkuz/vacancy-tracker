import { useState } from "preact/hooks";
import { createProfile, ProfileRejected, type Meta, type SearchProfile } from "../api/profiles";
import { emptyAnswers, wizardErrors, wizardProfile, type WizardAnswers } from "../domain/wizard";

interface Props {
  meta: Meta;
  onCreated: (profile: SearchProfile) => void;
  onSkip: () => void;
}

const WORK_MODES = ["remote", "hybrid", "onsite"];

/** First-run wizard (T-058): a few plain questions, then the first profile. The user's own words, no defaults. */
export function Wizard({ meta, onCreated, onSkip }: Props) {
  const [a, setA] = useState<WizardAnswers>(emptyAnswers);
  const [shown, setShown] = useState(false);
  const [saving, setSaving] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const errors = wizardErrors(a);
  const set = (patch: Partial<WizardAnswers>) => setA((x) => ({ ...x, ...patch }));
  const text = (key: keyof WizardAnswers) => (e: Event) => set({ [key]: (e.target as HTMLInputElement).value } as Partial<WizardAnswers>);
  const show = (key: string) => (shown ? errors[key] : undefined);

  const submit = async (e: Event) => {
    e.preventDefault();
    setShown(true);
    if (Object.keys(errors).length > 0) {
      return;
    }
    setSaving(true);
    setServerError(null);
    try {
      onCreated(await createProfile(wizardProfile(a, meta)));
    } catch (err: unknown) {
      setServerError(err instanceof ProfileRejected
        ? (err.status === 409 ? "A profile with this name already exists." : Object.values(err.errors).join(" ") || err.message || "The profile was not saved. Try again.")
        : "The profile was not saved. Try again.");
      setSaving(false);
    }
  };

  return (
    <form class="panel wizard" onSubmit={submit} noValidate aria-labelledby="wizard-title">
      <h2 id="wizard-title">Set up your first search profile</h2>
      <p class="muted">Answer a few questions. Nothing is preset: the profile contains only what you enter, and you can refine every rule afterwards.</p>
      {serverError && <div class="notice error" role="alert">{serverError}</div>}

      <label class="field"><span>1. Name this profile</span>
        <input class="input" type="text" value={a.name} onInput={text("name")} aria-invalid={show("name") ? "true" : undefined} />
        {show("name") && <span class="field-error" role="alert">{errors.name}</span>}
      </label>

      <label class="field"><span>2. What are you looking for? Words or phrases, separated by commas</span>
        <input class="input" type="text" value={a.keywords} onInput={text("keywords")} aria-invalid={show("keywords") ? "true" : undefined} />
        {show("keywords") && <span class="field-error" role="alert">{errors.keywords}</span>}
      </label>

      <fieldset class="editor-section">
        <legend>3. Where do you want to work? (optional)</legend>
        <div class="source-list">
          {WORK_MODES.map((m) => (
            <label key={m} class="check">
              <input type="checkbox" checked={a.workModes.includes(m)}
                onChange={(e) => set({ workModes: (e.target as HTMLInputElement).checked ? [...a.workModes, m] : a.workModes.filter((x) => x !== m) })} />
              {m}
            </label>
          ))}
        </div>
        <label class="field"><span>Countries, two-letter codes separated by commas</span>
          <input class="input" type="text" value={a.countries} onInput={text("countries")} />
        </label>
      </fieldset>

      <fieldset class="editor-section">
        <legend>4. Minimum salary (optional)</legend>
        <div class="threshold-row">
          <label class="field"><span>Amount</span>
            <input class="input num" type="text" inputMode="numeric" value={a.salaryAmount} onInput={text("salaryAmount")} aria-invalid={show("salaryAmount") ? "true" : undefined} />
            {show("salaryAmount") && <span class="field-error" role="alert">{errors.salaryAmount}</span>}
          </label>
          <label class="field"><span>Currency</span>
            <input class="input" type="text" maxLength={3} value={a.currency} onInput={text("currency")} aria-invalid={show("currency") ? "true" : undefined} />
            {show("currency") && <span class="field-error" role="alert">{errors.currency}</span>}
          </label>
          <label class="field"><span>Per</span>
            <select class="input" value={a.period} onChange={(e) => set({ period: (e.target as HTMLSelectElement).value as "year" | "month" })}>
              <option value="year">year</option>
              <option value="month">month</option>
            </select>
          </label>
        </div>
      </fieldset>

      <div class="toolbar">
        <button type="submit" class="btn primary" data-role="amber" disabled={saving}>{saving ? "Creating…" : "Create profile"}</button>
        <button type="button" class="btn" onClick={onSkip}>Skip and build it by hand</button>
      </div>
    </form>
  );
}
