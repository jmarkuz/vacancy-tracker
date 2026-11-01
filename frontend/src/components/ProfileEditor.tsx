import { useEffect, useRef, useState } from "preact/hooks";
import {
  createProfile,
  ProfileRejected,
  updateProfile,
  validateProfile,
  type Criterion,
  type Meta,
  type ProfileInput,
  type SearchProfile,
} from "../api/profiles";
import {
  duplicateRule,
  moveRule,
  newRule,
  profileErrors,
  removeRule,
  rowErrors,
  sameInput,
} from "../domain/profileEditor";
import { PreviewPanel } from "./PreviewPanel";
import { RuleRow } from "./RuleRow";

interface Props {
  initial: ProfileInput;
  /** The stored profile being edited, or null for a new one. */
  profile: SearchProfile | null;
  meta: Meta;
  sourceIds: string[];
  onSaved: (saved: SearchProfile, created: boolean) => void;
  onClose: () => void;
}

type Validity = "checking" | "valid" | "invalid" | "unknown";

/**
 * Profile editor with the rule builder (T-055). Every change is checked by the server's dry run, so errors
 * appear next to the exact field. Saving creates a new version; the previous one stays readable.
 */
export function ProfileEditor({ initial, profile, meta, sourceIds, onSaved, onClose }: Props) {
  const [draft, setDraft] = useState<ProfileInput>(initial);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [validity, setValidity] = useState<Validity>("checking");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const pendingFocus = useRef<{ id: string; role: string } | null>(null);

  const dirty = !sameInput(draft, initial);
  const header = profileErrors(errors);

  // Live validation: the server's dry run, a moment after the last edit.
  useEffect(() => {
    setValidity("checking");
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      validateProfile(draft, controller.signal)
        .then((result) => {
          setErrors(result.errors);
          setValidity(result.valid ? "valid" : "invalid");
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setValidity("unknown");
          }
        });
    }, 350);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [draft]);

  // After a move the element may have been re-created; put focus back on the same control of the same rule.
  useEffect(() => {
    const target = pendingFocus.current;
    if (!target) {
      return;
    }
    pendingFocus.current = null;
    const row = document.querySelector<HTMLElement>(`[data-rule="${CSS.escape(target.id)}"]`);
    const control = row?.querySelector<HTMLElement>(`[data-role="${target.role}"]:not(:disabled)`)
      ?? row?.querySelector<HTMLElement>('[data-role="label"]');
    control?.focus();
  }, [draft.criteria]);

  const edit = (patch: Partial<ProfileInput>) => setDraft((d) => ({ ...d, ...patch }));
  const setRules = (criteria: Criterion[]) => edit({ criteria });

  const move = (index: number, delta: number, role: string) => {
    const next = moveRule(draft.criteria, index, delta);
    if (next === draft.criteria) {
      return;
    }
    pendingFocus.current = { id: draft.criteria[index].id, role };
    setRules(next);
  };

  const addRule = () => {
    const rule = newRule(meta, draft.criteria);
    pendingFocus.current = { id: rule.id, role: "label" };
    setRules([...draft.criteria, rule]);
  };

  const duplicate = (index: number) => {
    const next = duplicateRule(draft.criteria, index);
    pendingFocus.current = { id: next[index + 1].id, role: "label" };
    setRules(next);
  };

  const save = async () => {
    setSaving(true);
    setSaveError(null);
    try {
      const saved = profile ? await updateProfile(profile.id, draft) : await createProfile(draft);
      onSaved(saved, !profile);
    } catch (e: unknown) {
      if (e instanceof ProfileRejected) {
        setErrors(e.status === 409 ? { ...errors, name: "A profile with this name already exists" } : e.errors);
        setSaveError(e.message);
        setValidity("invalid");
      } else {
        setSaveError("The profile was not saved. Check the connection and try again.");
      }
    } finally {
      setSaving(false);
    }
  };

  const allSources = draft.sources.includes("all");
  const toggleSource = (id: string, on: boolean) =>
    edit({ sources: on ? [...draft.sources, id] : draft.sources.filter((s) => s !== id) });
  const threshold = (key: keyof ProfileInput["thresholds"], text: string) =>
    edit({ thresholds: { ...draft.thresholds, [key]: Number(text) } });

  const canSave = !saving && validity === "valid" && (profile === null || dirty);
  return (
    <form class="profile-editor" onSubmit={(e) => { e.preventDefault(); if (canSave) { save(); } }} aria-label="Profile editor">
      <div class="toolbar">
        <h2>{profile ? `Edit ${profile.name}` : "New profile"}</h2>
        <span class="grow" />
        {profile && <span class="muted num small">Version {profile.version}</span>}
        {dirty && <span class="muted small" role="status">Unsaved changes</span>}
        <button type="button" class="btn" onClick={onClose}>Close</button>
        <button type="submit" class="btn primary" disabled={!canSave}>
          {saving ? "Saving…" : profile ? "Save as new version" : "Create profile"}
        </button>
      </div>

      {saveError && <div class="notice error" role="alert">{saveError}</div>}

      <fieldset class="editor-section">
        <legend>Profile</legend>
        <label class="field">
          <span>Name</span>
          <input class="input" type="text" value={draft.name} aria-invalid={header.name ? "true" : undefined}
            aria-describedby={header.name ? "err-name" : undefined}
            onInput={(e) => edit({ name: (e.target as HTMLInputElement).value })} />
          {header.name && <span class="field-error" id="err-name" role="alert">{header.name}</span>}
        </label>
        <label class="toggle">
          <input type="checkbox" role="switch" checked={draft.enabled}
            onChange={(e) => edit({ enabled: (e.target as HTMLInputElement).checked })} />
          <span>{draft.enabled ? "Enabled" : "Disabled"}</span>
        </label>
      </fieldset>

      <fieldset class="editor-section">
        <legend>Sources</legend>
        <label class="check">
          <input type="checkbox" checked={allSources} onChange={(e) => edit({ sources: (e.target as HTMLInputElement).checked ? ["all"] : [] })} />
          All sources
        </label>
        <div class="source-list">
          {sourceIds.map((id) => (
            <label key={id} class="check">
              <input type="checkbox" disabled={allSources} checked={allSources || draft.sources.includes(id)}
                onChange={(e) => toggleSource(id, (e.target as HTMLInputElement).checked)} />
              {id}
            </label>
          ))}
        </div>
        {header.sources && <span class="field-error" role="alert">{header.sources}</span>}
      </fieldset>

      <fieldset class="editor-section">
        <legend>Thresholds and schedule</legend>
        <div class="threshold-row">
          <label class="field"><span>Inbox minimum score</span>
            <input class="input num" type="number" min={0} max={100} value={draft.thresholds.inboxMinScore}
              onInput={(e) => threshold("inboxMinScore", (e.target as HTMLInputElement).value)} />
            {header["thresholds.inboxMinScore"] && <span class="field-error" role="alert">{header["thresholds.inboxMinScore"]}</span>}
          </label>
          <label class="field"><span>Notify minimum score</span>
            <input class="input num" type="number" min={0} max={100} value={draft.thresholds.notifyMinScore}
              onInput={(e) => threshold("notifyMinScore", (e.target as HTMLInputElement).value)} />
            {header["thresholds.notifyMinScore"] && <span class="field-error" role="alert">{header["thresholds.notifyMinScore"]}</span>}
          </label>
          <label class="field"><span>Ignore older than (days)</span>
            <input class="input num" type="number" min={1} value={draft.thresholds.maxAgeDays}
              onInput={(e) => threshold("maxAgeDays", (e.target as HTMLInputElement).value)} />
            {header["thresholds.maxAgeDays"] && <span class="field-error" role="alert">{header["thresholds.maxAgeDays"]}</span>}
          </label>
          <label class="field"><span>Schedule override (cron, optional)</span>
            <input class="input mono" type="text" placeholder="0 0 6 * * *" value={draft.scheduleOverride ?? ""}
              onInput={(e) => edit({ scheduleOverride: (e.target as HTMLInputElement).value || null })} />
            {header.scheduleOverride && <span class="field-error" role="alert">{header.scheduleOverride}</span>}
          </label>
        </div>
      </fieldset>

      <section class="editor-section" aria-labelledby="rules-title">
        <div class="toolbar">
          <h3 id="rules-title">Rules</h3>
          <span class="grow" />
          <span class="muted small keyboard-hint">Alt+↑ / Alt+↓ moves the focused rule</span>
          <button type="button" class="btn" onClick={addRule}>Add rule</button>
        </div>
        {draft.criteria.length === 0 ? (
          <p class="muted">No rules yet. A profile without rules scores every vacancy 0 and filters nothing.</p>
        ) : (
          <ol class="rules" aria-label="Rules">
            {draft.criteria.map((rule, i) => (
              <RuleRow key={rule.id} rule={rule} index={i} count={draft.criteria.length} meta={meta}
                errors={rowErrors(errors, i)}
                onChange={(next) => setRules(draft.criteria.map((r, j) => (j === i ? next : r)))}
                onMove={(delta, role) => move(i, delta, role)}
                onDuplicate={() => duplicate(i)}
                onRemove={() => setRules(removeRule(draft.criteria, i))} />
            ))}
          </ol>
        )}
      </section>

      <PreviewPanel draft={draft} valid={validity === "valid"} />

      <p class="muted small" role="status" aria-live="polite">
        {validity === "checking" ? "Checking…" : validity === "valid" ? "The profile is valid." : validity === "invalid" ? "Fix the marked fields to save." : "Could not check the profile right now."}
      </p>
    </form>
  );
}
