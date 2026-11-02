import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  createProfile,
  deleteProfile,
  exportUrl,
  importProfile,
  ProfileRejected,
  getMeta,
  listProfiles,
  type Meta,
  type ProfileInput,
  type SearchProfile,
} from "../api/profiles";
import { getRecalculationProgress, startRecalculation, type RecalculationProgress } from "../api/recalculation";
import { listSources } from "../api/sources";
import { copyOfProfile, emptyProfile, importProblems, toInput } from "../domain/profileEditor";
import { percentDone, progressText } from "../domain/recalc";
import { ProfileEditor } from "../components/ProfileEditor";
import { CompanyLists } from "../components/CompanyLists";
import { Wizard } from "../components/Wizard";
import { skipWizard } from "../domain/firstRun";
import { Toast } from "../components/Toast";
import { setQuery } from "../router";
import { formatDate } from "./columns";

const POLL_MS = 700;
// A job that was just requested may take a moment to show up; about 20 seconds is far more than it needs.
const MAX_IDLE_POLLS = 30;

interface Props {
  params: URLSearchParams;
}

/**
 * Search profiles: the list, the rule builder (T-055), duplicating and deleting profiles, and the
 * "Recalculate scores" button with live progress (T-053). `?edit=<id>` or `?edit=new` opens the editor.
 */
export function Profiles({ params }: Props) {
  const [profiles, setProfiles] = useState<SearchProfile[] | null>(null);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [sourceIds, setSourceIds] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [progress, setProgress] = useState<RecalculationProgress | null>(null);
  const [importErrors, setImportErrors] = useState<string[] | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const [toast, setToast] = useState<{ message: string; tone: "info" | "error" } | null>(null);
  const timer = useRef<number | undefined>(undefined);
  const editing = params.get("edit");
  const wizard = params.get("wizard") === "1" && !editing;

  const load = () =>
    listProfiles()
      .then((list) => {
        setProfiles(list);
        setError(null);
      })
      .catch((e: unknown) => setError(e instanceof ApiError ? e.message : "Could not load the profiles."));

  useEffect(() => {
    load();
    getMeta().then(setMeta).catch(() => setError("Could not load the rule builder options."));
    listSources().then((s) => setSourceIds(s.map((x) => x.id))).catch(() => undefined);
    // Pick up a job that is already running, for example one started by saving a profile.
    getRecalculationProgress()
      .then((p) => {
        if (p) {
          setProgress(p);
          if (p.state === "RUNNING") {
            watch();
          }
        }
      })
      .catch(() => undefined);
    return () => window.clearInterval(timer.current);
  }, []);

  /** Polls until the job has finished, then shows the summary once. */
  const watch = () => {
    window.clearInterval(timer.current);
    let idle = 0;
    timer.current = window.setInterval(async () => {
      try {
        const p = await getRecalculationProgress();
        if (!p) {
          // No job to follow: stop after a few empty answers instead of asking for ever.
          idle += 1;
          if (idle >= MAX_IDLE_POLLS) {
            window.clearInterval(timer.current);
            setToast({ message: "No recalculation is running. It may already have finished.", tone: "info" });
          }
          return;
        }
        idle = 0;
        setProgress(p);
        if (p.state === "COMPLETED") {
          window.clearInterval(timer.current);
          setToast({ message: progressText(p), tone: p.failed > 0 ? "error" : "info" });
        }
      } catch {
        window.clearInterval(timer.current);
        setToast({ message: "Lost track of the recalculation. It may still be running.", tone: "error" });
      }
    }, POLL_MS);
  };

  const recalculate = async () => {
    setToast(null);
    try {
      const started = await startRecalculation();
      if (!started.started) {
        setToast({ message: "A recalculation is already running. It will run once more when it finishes.", tone: "info" });
      }
      const current = await getRecalculationProgress();
      if (current) {
        setProgress(current);
      }
      watch();
    } catch (e: unknown) {
      setToast({ message: e instanceof Error ? e.message : "Could not start the recalculation.", tone: "error" });
    }
  };

  const duplicate = async (profile: SearchProfile) => {
    try {
      const copy = await createProfile(copyOfProfile(profile, (profiles ?? []).map((p) => p.name)));
      await load();
      setToast({ message: `Created "${copy.name}"`, tone: "info" });
      setQuery({ edit: copy.id });
    } catch (e: unknown) {
      setToast({ message: e instanceof Error ? e.message : "Could not duplicate the profile.", tone: "error" });
    }
  };

  const remove = async (profile: SearchProfile) => {
    if (!window.confirm(`Delete the profile "${profile.name}"? Its version history is deleted too.`)) {
      return;
    }
    try {
      await deleteProfile(profile.id);
      await load();
      setToast({ message: `Deleted "${profile.name}"`, tone: "info" });
    } catch (e: unknown) {
      setToast({ message: e instanceof Error ? e.message : "Could not delete the profile.", tone: "error" });
    }
  };

  const importFile = async (file: File) => {
    setImportErrors(null);
    try {
      const result = await importProfile(await file.text());
      await load();
      setToast({
        message: result.created
          ? `Imported "${result.profile.name}" as a new profile`
          : `Imported "${result.profile.name}" as version ${result.profile.version} of the existing profile`,
        tone: "info",
      });
      setQuery({ edit: result.profile.id });
    } catch (e: unknown) {
      setImportErrors(e instanceof ProfileRejected ? importProblems(e.errors) : ["Could not read or send the file."]);
    }
  };

  const running = progress?.state === "RUNNING";
  const stored = editing && editing !== "new" ? profiles?.find((p) => p.id === editing) ?? null : null;
  const initial: ProfileInput | null =
    editing === "new" && meta ? emptyProfile(meta) : stored ? toInput(stored) : null;

  return (
    <section aria-labelledby="profiles-title">
      <div class="toolbar">
        <h1 id="profiles-title">Search profiles</h1>
        <span class="grow" />
        <button class="btn" type="button" onClick={() => setQuery({ edit: "new" })} disabled={!meta}>
          New profile
        </button>
        <button class="btn" type="button" onClick={() => fileInput.current?.click()}>Import</button>
        <input ref={fileInput} type="file" accept=".yml,.yaml,.json" hidden aria-label="Profile file to import" data-role="import-file"
          onChange={(e) => {
            const input = e.target as HTMLInputElement;
            const file = input.files?.[0];
            input.value = "";
            if (file) {
              importFile(file);
            }
          }} />
        <button class="btn" type="button" onClick={recalculate} disabled={running} aria-busy={running}>
          {running ? "Recalculating…" : "Recalculate scores"}
        </button>
      </div>

      {progress && (
        <div class="recalc-progress" role="status" aria-live="polite">
          <progress max={100} value={percentDone(progress)} aria-label="Recalculation progress" />
          <span class="muted num">{progressText(progress)}</span>
        </div>
      )}

      {error && <div class="notice error" role="alert">{error}</div>}

      {importErrors && (
        <div class="notice error" role="alert">
          <p>The file was not imported. Nothing was changed.</p>
          <ul>{importErrors.map((line) => <li key={line} class="mono">{line}</li>)}</ul>
          <button class="btn" type="button" onClick={() => setImportErrors(null)}>Dismiss</button>
        </div>
      )}

      {wizard && meta && (
        <Wizard meta={meta}
          onSkip={() => { skipWizard(); setQuery({ wizard: null, edit: "new" }); }}
          onCreated={async (created) => {
            // The list must hold the new profile before the editor looks for it, or "no longer exists" flashes.
            await load();
            setToast({ message: `Created "${created.name}". Review its rules below. Scores are being recalculated.`, tone: "info" });
            watch();
            setQuery({ wizard: null, edit: created.id });
          }} />
      )}

      {initial && meta && (
        <ProfileEditor
          key={editing ?? "none"}
          initial={initial}
          profile={stored}
          meta={meta}
          sourceIds={sourceIds}
          onClose={() => setQuery({ edit: null })}
          onSaved={async (saved, created) => {
            await load();
            setToast({
              message: created ? `Created "${saved.name}". Scores are being recalculated.` : `Saved "${saved.name}" as version ${saved.version}. Scores are being recalculated.`,
              tone: "info",
            });
            setProgress(null);
            watch();
            setQuery({ edit: saved.id });
          }}
        />
      )}

      {editing && !initial && profiles && meta && (
        <div class="notice error" role="alert">
          <span>That profile no longer exists.</span>
          <button class="btn" type="button" onClick={() => setQuery({ edit: null })}>Back to the list</button>
        </div>
      )}

      {profiles && profiles.length === 0 && !editing && !wizard && (
        <div class="panel empty-state">
          <h2>No search profiles yet</h2>
          <p class="muted">A profile says what you look for. Create one to score and filter vacancies.</p>
          <button class="btn primary" data-role="amber" type="button" onClick={() => setQuery({ wizard: "1" })} disabled={!meta}>
            Set up with the wizard
          </button>
          <button class="btn" type="button" onClick={() => setQuery({ edit: "new" })} disabled={!meta}>
            New profile
          </button>
        </div>
      )}

      {profiles && profiles.length > 0 && (
        <div class="table-wrap">
          <table aria-label="Search profiles">
            <thead>
              <tr>
                <th scope="col">Name</th>
                <th scope="col">State</th>
                <th scope="col">Sources</th>
                <th scope="col" class="numeric">Rules</th>
                <th scope="col" class="numeric">Version</th>
                <th scope="col">Updated</th>
                <th scope="col"><span class="sr-only">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {profiles.map((p) => (
                <tr key={p.id} class={p.id === editing ? "editing" : undefined}>
                  <td><button type="button" class="row-open" onClick={() => setQuery({ edit: p.id })}>{p.name}</button></td>
                  <td>{p.enabled ? "Enabled" : "Disabled"}</td>
                  <td>{p.sources.join(", ")}</td>
                  <td class="numeric num">{p.criteria.length}</td>
                  <td class="numeric num">{p.version}</td>
                  <td class="num">{formatDate(p.updatedAt)}</td>
                  <td class="row-actions">
                    <button type="button" class="btn" onClick={() => setQuery({ edit: p.id })}>Edit</button>
                    <button type="button" class="btn" onClick={() => duplicate(p)}>Duplicate</button>
                    <a class="btn" href={exportUrl(p.id, "yaml")} download>Export YAML</a>
                    <a class="btn" href={exportUrl(p.id, "json")} download>Export JSON</a>
                    <button type="button" class="btn ghost" onClick={() => remove(p)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {profiles && !editing && !wizard && (
        <CompanyLists profiles={profiles} onChanged={(message) => { setToast({ message, tone: "info" }); setProgress(null); watch(); }} />
      )}

      {toast && <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />}
    </section>
  );
}
