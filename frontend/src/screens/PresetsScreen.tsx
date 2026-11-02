import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  createPreset,
  deletePreset,
  duplicatePreset,
  getPresetDefaults,
  listPresets,
  makeDefaultPreset,
  pasteBaseResume,
  type Preset,
  type PresetDefaults,
  resetBaseResume,
  updatePreset,
  uploadBaseCoverLetter,
  uploadBaseResume,
} from "../api/presets";
import { placeholders, shares, weightsProblem } from "../domain/presets";
import { setQuery } from "../router";

/** The editable copy of a preset; saved with one PATCH. */
interface Form {
  name: string;
  mainGoal: string;
  weights: { name: string; weight: number }[];
  resumePrompt: string;
  coverLetterPrompt: string;
  baseCoverLetter: string;
  baseResume: string;
}

const formOf = (p: Preset): Form => ({
  name: p.name,
  mainGoal: p.mainGoal,
  weights: p.weights.map((w) => ({ name: w.name, weight: w.weight })),
  resumePrompt: p.resumePrompt,
  coverLetterPrompt: p.coverLetterPrompt,
  baseCoverLetter: p.baseCoverLetter,
  baseResume: p.baseResume.text,
});

const FILE_TYPES =
  ".pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown";

/**
 * AI presets (T-093): the search goal, the weights of its parameters, the prompts of both documents and the base
 * documents AI adapts. The base resume is the same as on the Resume screen for the default preset. A field with a
 * {{placeholder}} keeps generation off until it is filled.
 */
export function PresetsScreen({ params }: { params: URLSearchParams }) {
  const [presets, setPresets] = useState<Preset[]>([]);
  const [defaults, setDefaults] = useState<PresetDefaults | null>(null);
  const [form, setForm] = useState<Form | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const selected = presets.find((p) => p.id === params.get("id")) ?? presets.find((p) => p.isDefault) ?? presets[0];

  useEffect(() => {
    const controller = new AbortController();
    Promise.all([listPresets(controller.signal), getPresetDefaults(controller.signal)])
      .then(([list, d]) => {
        setPresets(list);
        setDefaults(d);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : "Could not load the presets.");
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, []);

  // A fresh form whenever another preset is chosen or the chosen one comes back from the server.
  // A fresh form only when another preset is chosen. After a save only the saved fields are taken from the server,
  // so unsaved edits elsewhere on the screen survive.
  useEffect(() => setForm(selected ? formOf(selected) : null), [selected?.id]);

  /** Stores the preset the server returned and copies the given fields of it into the form. */
  const applySaved = (p: Preset, fields: (keyof Form)[]) => {
    replace(p);
    const fresh = formOf(p);
    setForm((current) => current && Object.assign({ ...current }, Object.fromEntries(fields.map((k) => [k, fresh[k]]))));
  };
  const PRESET_FIELDS: (keyof Form)[] = ["name", "mainGoal", "weights", "resumePrompt", "coverLetterPrompt", "baseCoverLetter"];

  const replace = (p: Preset) => setPresets((list) => (list.some((x) => x.id === p.id) ? list.map((x) => (x.id === p.id ? p : x)) : [...list, p]));

  const run = async (action: () => Promise<void>, done: string) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await action();
      setNotice(done);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "That did not work. Nothing was changed.");
    } finally {
      setBusy(false);
    }
  };

  const reload = async (select?: string) => {
    const list = await listPresets();
    setPresets(list);
    if (select) {
      setQuery({ id: select });
    }
  };

  if (loading) {
    return <p class="muted" role="status">Loading…</p>;
  }
  if (!selected || !form || !defaults) {
    return (
      <div class="notice error" role="alert">
        {error ?? "No preset to show."}
      </div>
    );
  }

  const set = (patch: Partial<Form>) => setForm({ ...form, ...patch });
  const original = formOf(selected);
  const changed: Partial<Form> = {};
  for (const key of ["name", "mainGoal", "resumePrompt", "coverLetterPrompt", "baseCoverLetter"] as const) {
    if (form[key] !== original[key]) {
      changed[key] = form[key];
    }
  }
  const weightsChanged = JSON.stringify(form.weights) !== JSON.stringify(original.weights);
  const weightIssue = weightsProblem(form.weights);
  const dirty = Object.keys(changed).length > 0 || weightsChanged;
  const baseResumeChanged = form.baseResume !== original.baseResume;
  const percent = shares(form.weights);

  const save = () =>
    run(async () => {
      const p = await updatePreset(selected.id, { ...changed, ...(weightsChanged ? { weights: form.weights } : {}) });
      applySaved(p, PRESET_FIELDS);
    }, "Saved the preset.");

  return (
    <section aria-labelledby="presets-title" class="stack">
      <div class="toolbar">
        <h1 id="presets-title">Presets</h1>
        <span class="grow" />
        <label class="small">
          <span class="sr-only">Preset</span>
          <select class="input" aria-label="Preset" value={selected.id} onChange={(e) => setQuery({ id: (e.target as HTMLSelectElement).value })}>
            {presets.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.isDefault ? " (default)" : ""}
              </option>
            ))}
          </select>
        </label>
        <button
          class="btn"
          type="button"
          disabled={busy}
          onClick={() => {
            const name = window.prompt("Name of the new preset");
            if (name) {
              run(async () => {
                const p = await createPreset(name);
                await reload(p.id);
              }, `Created ${name}.`);
            }
          }}
        >
          New preset
        </button>
        <button class="btn" type="button" disabled={busy} onClick={() => run(async () => reload((await duplicatePreset(selected.id)).id), "Duplicated the preset.")}>
          Duplicate
        </button>
        <button
          class="btn"
          type="button"
          disabled={busy || selected.isDefault}
          onClick={() => run(async () => { await makeDefaultPreset(selected.id); await reload(selected.id); }, `${selected.name} is now the default.`)}
        >
          {selected.isDefault ? "Default preset" : "Make default"}
        </button>
        <button
          class="btn ghost"
          type="button"
          disabled={busy || presets.length <= 1}
          title={presets.length <= 1 ? "The last preset cannot be deleted." : undefined}
          onClick={() => {
            if (window.confirm(`Delete the preset ${selected.name}? Saved document versions keep their own copy.`)) {
              run(async () => {
                await deletePreset(selected.id);
                setQuery({ id: null });
                await reload();
              }, "Deleted the preset.");
            }
          }}
        >
          Delete
        </button>
      </div>

      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div class="notice" role="status">
          {notice}
        </div>
      )}

      <Readiness preset={selected} />

      <div class="panel stack">
        <label>
          <span class="small">Name</span>
          <input class="input" value={form.name} maxLength={100} onInput={(e) => set({ name: (e.target as HTMLInputElement).value })} />
        </label>
        <TextField label="Main goal prompt" value={form.mainGoal} fallback={defaults.mainGoal} rows={12} onChange={(mainGoal) => set({ mainGoal })} />

        <fieldset class="stack">
          <legend>Weights of the goal's parameters</legend>
          <p class="muted small">Weights are relative: the share shows how much each parameter counts in the Goal match.</p>
          <div class="table-wrap">
          <table aria-label="Weights">
            <thead>
              <tr>
                <th scope="col">Parameter</th>
                <th scope="col">Weight</th>
                <th scope="col">Share</th>
                <th scope="col"><span class="sr-only">Remove</span></th>
              </tr>
            </thead>
            <tbody>
              {form.weights.map((w, i) => (
                <tr key={i}>
                  <td>
                    <input
                      class="input"
                      aria-label={`Parameter ${i + 1} name`}
                      value={w.name}
                      maxLength={80}
                      onInput={(e) => set({ weights: form.weights.map((x, j) => (j === i ? { ...x, name: (e.target as HTMLInputElement).value } : x)) })}
                    />
                  </td>
                  <td>
                    <input
                      class="input num"
                      type="number"
                      min={0}
                      max={1000}
                      step={1}
                      aria-label={`${w.name || `Parameter ${i + 1}`} weight`}
                      value={w.weight}
                      onInput={(e) => set({ weights: form.weights.map((x, j) => (j === i ? { ...x, weight: Number((e.target as HTMLInputElement).value) } : x)) })}
                    />
                  </td>
                  <td class="num">{percent[i]}%</td>
                  <td>
                    <button class="btn ghost" type="button" aria-label={`Remove ${w.name || `parameter ${i + 1}`}`}
                      onClick={() => set({ weights: form.weights.filter((_, j) => j !== i) })}>
                      Remove
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          </div>
          <div class="drawer-row">
            <button class="btn ghost" type="button" disabled={form.weights.length >= 20}
              onClick={() => set({ weights: [...form.weights, { name: "", weight: 10 }] })}>
              Add parameter
            </button>
            <button class="btn ghost" type="button" onClick={() => set({ weights: defaults.weights.map((w) => ({ ...w })) })}>
              Reset to default
            </button>
          </div>
          {weightIssue && <p class="notice error small" role="alert">{weightIssue}</p>}
        </fieldset>

        <TextField label="Resume prompt" value={form.resumePrompt} fallback={defaults.resumePrompt} rows={12} onChange={(resumePrompt) => set({ resumePrompt })} />
        <TextField label="Cover letter prompt" value={form.coverLetterPrompt} fallback={defaults.coverLetterPrompt} rows={10}
          onChange={(coverLetterPrompt) => set({ coverLetterPrompt })} />
        <TextField label="Base cover letter" value={form.baseCoverLetter} fallback={defaults.baseCoverLetter} rows={10}
          note={selected.baseCoverLetterFileName ? `From ${selected.baseCoverLetterFileName}.` : undefined}
          onChange={(baseCoverLetter) => set({ baseCoverLetter })}>
          <FileButton label="Upload base cover letter" busy={busy}
            onFile={(file) => run(async () => applySaved(await uploadBaseCoverLetter(selected.id, file), ["baseCoverLetter"]), `Read ${file.name}.`)} />
        </TextField>

        <div class="drawer-row end">
          <button class="btn primary" type="button" disabled={busy || !dirty || weightIssue !== null} onClick={save}>
            Save preset
          </button>
        </div>
      </div>

      <div class="panel stack">
        <h2>Base resume</h2>
        <p class="muted small">
          {selected.isDefault
            ? "The default preset's base resume is your resume on the Resume screen: changing one changes the other."
            : "This preset's own base resume. It becomes your active resume when the preset is made the default."}{" "}
          {selected.baseResume.template
            ? "It is still the default template: upload or paste your resume."
            : `${selected.baseResume.name}${selected.baseResume.fileName ? ` (${selected.baseResume.fileName})` : ""}.`}
        </p>
        <TextField label="Base resume text" value={form.baseResume} fallback={defaults.baseResume} rows={16} onChange={(baseResume) => set({ baseResume })}
          onReset={() => {
            if (window.confirm(selected.isDefault ? "Put the template back? Your resume will no longer be the active resume." : "Put the template back?")) {
              run(async () => applySaved(await resetBaseResume(selected.id), ["baseResume"]), "The base resume is the default template again.");
            }
          }}>
          <FileButton label="Upload base resume" busy={busy}
            onFile={(file) => run(async () => applySaved(await uploadBaseResume(selected.id, file), ["baseResume"]), `Read ${file.name}.`)} />
          <button class="btn primary" type="button" disabled={busy || !baseResumeChanged || !form.baseResume.trim()}
            onClick={() => run(async () => applySaved(await pasteBaseResume(selected.id, form.baseResume), ["baseResume"]), "Saved the base resume.")}>
            Save base resume
          </button>
        </TextField>
      </div>

      <details class="panel">
        <summary>Guardrail (always sent first, not editable)</summary>
        <pre class="prewrap small">{defaults.guardrail}</pre>
      </details>
    </section>
  );
}

/** What still keeps each document from being generated. */
function Readiness({ preset }: { preset: Preset }) {
  const rows: [string, typeof preset.readiness.resume][] = [
    ["Resume", preset.readiness.resume],
    ["Cover letter", preset.readiness.coverLetter],
  ];
  return (
    <div class="panel" aria-label="Ready for AI generation">
      {rows.map(([label, missing]) => (
        <p key={label} class="small">
          <strong>{label}:</strong>{" "}
          {missing.length === 0 ? "ready for AI generation." : `not ready. ${missing.map((m) => m.message).join(" ")}`}
        </p>
      ))}
    </div>
  );
}

function TextField(props: {
  label: string;
  value: string;
  fallback: string;
  rows: number;
  note?: string;
  onChange: (value: string) => void;
  onReset?: () => void;
  children?: preact.ComponentChildren;
}) {
  const marks = placeholders(props.value);
  const id = `field-${props.label.replace(/\W+/g, "-").toLowerCase()}`;
  return (
    <div class="stack">
      <label for={id} class="small">
        {props.label}
      </label>
      <textarea id={id} class="input textarea" rows={props.rows} value={props.value} aria-describedby={marks.length ? `${id}-marks` : undefined}
        onInput={(e) => props.onChange((e.target as HTMLTextAreaElement).value)} />
      {props.note && <p class="muted small">{props.note}</p>}
      {marks.length > 0 && (
        <p id={`${id}-marks`} class="small warn-text">
          Fill in before AI can use it: {marks.map((m) => `{{${m}}}`).join(", ")}
        </p>
      )}
      <div class="drawer-row">
        <button class="btn ghost" type="button" onClick={() => (props.onReset ? props.onReset() : props.onChange(props.fallback))}>
          Reset to default
        </button>
        {props.children}
      </div>
    </div>
  );
}

function FileButton({ label, busy, onFile }: { label: string; busy: boolean; onFile: (file: File) => void }) {
  return (
    <label class="btn file-button">
      {busy ? "Working…" : label}
      <input class="sr-only" type="file" aria-label={label} accept={FILE_TYPES} disabled={busy}
        onChange={(e) => {
          const input = e.target as HTMLInputElement;
          const file = input.files?.[0];
          if (file) {
            onFile(file);
          }
          input.value = "";
        }} />
    </label>
  );
}
