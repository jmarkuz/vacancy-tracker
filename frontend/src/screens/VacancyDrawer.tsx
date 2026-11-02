import { useEffect, useRef, useState } from "preact/hooks";
import { isFieldTarget } from "../domain/triage";
import { ApiError } from "../api/client";
import { changeStatus, getHistory, getVacancy, patchVacancy, type StatusEvent } from "../api/vacancies";
import type { Vacancy } from "../api/types";
import { allowedNext } from "../domain/statuses";
import { safeLink } from "../domain/links";
import { FitPanel } from "../components/FitPanel";
import { DocumentsPanel } from "../components/DocumentsPanel";
import { ApplyPanel } from "../components/ApplyPanel";
import { VacancyMailPanel } from "../components/VacancyMailPanel";
import { markSubmitted } from "../api/ai";
import { StatusBadge } from "../components/StatusBadge";
import { formatDate, formatSalary } from "./columns";

interface Props {
  id: string;
  onClose: () => void;
  /** Called after any save so the table can refresh its row. */
  onChange: () => void;
}

/** Side drawer on desktop, bottom sheet on phones (design README). Opened by ?open=<id>. */
/** Keeps Tab and Shift+Tab inside the open dialog. */
function trapTab(e: KeyboardEvent, panel: HTMLElement | null) {
  if (e.key !== "Tab" || !panel) {
    return;
  }
  const focusable = Array.from(
    panel.querySelectorAll<HTMLElement>("a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex='-1'])"),
  );
  if (focusable.length === 0) {
    e.preventDefault();
    return;
  }
  const first = focusable[0];
  const last = focusable[focusable.length - 1];
  if (e.shiftKey && (document.activeElement === first || document.activeElement === panel)) {
    e.preventDefault();
    last.focus();
  } else if (!e.shiftKey && document.activeElement === last) {
    e.preventDefault();
    first.focus();
  }
}

export function VacancyDrawer({ id, onClose, onChange }: Props) {
  const [vacancy, setVacancy] = useState<Vacancy | null>(null);
  const [history, setHistory] = useState<StatusEvent[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  // Grows when a document version is saved, so the Apply section offers it.
  const [docsVersion, setDocsVersion] = useState(0);
  const panelRef = useRef<HTMLElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setVacancy(null);
    setLoadError(null);
    Promise.all([getVacancy(id, controller.signal), getHistory(id, controller.signal)])
      .then(([v, h]) => {
        setVacancy(v);
        setHistory(h);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setLoadError(e instanceof ApiError ? e.message : "Could not load this vacancy.");
        }
      });
    return () => controller.abort();
  }, [id]);

  // Focus the panel once when it opens; re-running on every render would steal focus from the form.
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    panelRef.current?.focus();
    // Give focus back to what opened the drawer, so a keyboard user does not start again from the top of the page.
    return () => opener?.focus();
  }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        // In a field or an open list, Escape leaves the field first: it must not throw away an unsaved draft.
        if (isFieldTarget(e.target)) {
          (e.target as HTMLElement).blur();
          return;
        }
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  /** Runs a save, replaces the shown vacancy with the server copy, and reloads history if needed. */
  const currentId = useRef(id);
  currentId.current = id;

  const save = async (action: () => Promise<Vacancy>, reloadHistory = false) => {
    const savedFor = id;
    setSaving(true);
    setSaveError(null);
    let updated: Vacancy;
    try {
      updated = await action();
    } catch (e: unknown) {
      setSaveError(e instanceof ApiError ? e.message : "Save failed. Your change was not stored.");
      setSaving(false);
      return;
    }
    // Stored: the list behind the drawer must show it even if reloading the history below fails.
    onChange();
    // The drawer may have moved to another vacancy while this was in flight: its data must not land there.
    if (currentId.current === savedFor) {
      setVacancy(updated);
      if (reloadHistory) {
        try {
          const history = await getHistory(savedFor);
          if (currentId.current === savedFor) {
            setHistory(history);
          }
        } catch {
          setSaveError("Saved. The history could not be reloaded; reopen the vacancy to see it.");
        }
      }
    }
    setSaving(false);
  };

  const titleId = "drawer-title";
  return (
    <>
      <div class="scrim" onClick={onClose} aria-hidden="true" />
      <aside
        ref={panelRef}
        class="drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={(e) => trapTab(e as KeyboardEvent, panelRef.current)}
      >
        <header class="drawer-header">
          <div>
            <h2 id={titleId}>{vacancy?.title ?? (loadError ? "Vacancy" : "Loading…")}</h2>
            {vacancy && <p class="muted">{vacancy.company}</p>}
          </div>
          <button class="btn ghost" type="button" onClick={onClose} aria-label="Close vacancy">
            Close
          </button>
        </header>

        {loadError && (
          <div class="notice error" role="alert">
            {loadError}
          </div>
        )}

        {vacancy && (
          <div class="drawer-body">
            {saveError && (
              <div class="notice error" role="alert">
                {saveError}
              </div>
            )}

            <section class="drawer-section">
              <StatusMover
                key={`${vacancy.id}-${vacancy.status}`}
                status={vacancy.status}
                disabled={saving}
                onMove={(next) => save(() => changeStatus(id, next), true)}
              />
              <dl class="facts">
                <dt>Location</dt>
                <dd>{vacancy.location ?? "-"}</dd>
                <dt>Work mode</dt>
                <dd>{vacancy.workMode ?? "-"}</dd>
                <dt>Salary</dt>
                <dd class="num">
                  {formatSalary(vacancy)}
                  {vacancy.userEditedFields.includes("salary") && <EditedMark />}
                </dd>
                <dt>Visa sponsorship</dt>
                <dd>{vacancy.visaSponsorship === true ? "Offered" : vacancy.visaSponsorship === false ? "Not offered" : "Not stated"}</dd>
                <dt>Last seen</dt>
                <dd class="num">{formatDate(vacancy.lastSeenAt)}</dd>
                <dt>Original</dt>
                <dd>
                  {safeLink(vacancy.url) ? (
                    <a href={safeLink(vacancy.url) ?? undefined} target="_blank" rel="noopener noreferrer">
                      Open posting
                    </a>
                  ) : (
                    "-"
                  )}
                </dd>
              </dl>
            </section>

            <FitPanel vacancyId={vacancy.id} />

            <DocumentsPanel
              vacancyId={vacancy.id}
              title={vacancy.title}
              company={vacancy.company}
              onSaved={() => setDocsVersion((n) => n + 1)}
            />

            <ApplyPanel
              key={vacancy.id}
              vacancyId={vacancy.id}
              vacancyUrl={safeLink(vacancy.url)}
              docsVersion={docsVersion}
              onSubmitted={async (packageId, note) => {
                await markSubmitted(packageId, note || undefined);
                await save(() => getVacancy(id), true);
              }}
            />

            <DescriptionEditor
              key={vacancy.id + vacancy.userEditedFields.includes("description")}
              value={vacancy.description ?? ""}
              edited={vacancy.userEditedFields.includes("description")}
              disabled={saving}
              onSave={(text) => save(() => patchVacancy(id, { description: text }))}
            />

            <VacancyMailPanel vacancyId={vacancy.id} onStatusChanged={() => save(() => getVacancy(id), true)} />

            <NotesEditor
              key={`notes-${vacancy.id}`}
              value={vacancy.notes ?? ""}
              disabled={saving}
              onSave={(text) => save(() => patchVacancy(id, { notes: text }))}
            />

            <PriorityEditor
              key={`${vacancy.id}-${vacancy.priority ?? ""}`}
              value={vacancy.priority}
              disabled={saving}
              onSave={(priority) => save(() => patchVacancy(id, { priority }))}
            />

            <section class="drawer-section">
              <h3>History</h3>
              {history.length === 0 ? (
                <p class="muted">No status changes yet.</p>
              ) : (
                <ol class="history">
                  {history.map((h, i) => (
                    <li key={`${h.at}-${i}`}>
                      <span class="num">{formatDate(h.at)}</span> · {h.from ? `${h.from} → ` : ""}
                      {h.to}
                      {h.note && <span class="muted"> · {h.note}</span>}
                    </li>
                  ))}
                </ol>
              )}
            </section>
          </div>
        )}
      </aside>
    </>
  );
}

/**
 * Choosing a status and applying it are two steps. On Windows, arrow keys and type-ahead on a closed select change
 * its value at once, so committing on change recorded a status (often one with no way back) by accident.
 */
function StatusMover({ status, disabled, onMove }: { status: string; disabled: boolean; onMove: (next: string) => void }) {
  const options = allowedNext(status);
  const [next, setNext] = useState("");
  return (
    <div class="drawer-row">
      <StatusBadge status={status} />
      <label>
        <span class="sr-only">Next status</span>
        <select
          class="input"
          disabled={disabled || options.length === 0}
          value={next}
          onChange={(e) => setNext((e.target as HTMLSelectElement).value)}
        >
          <option value="">{options.length ? "Move to…" : "No further steps"}</option>
          {options.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
      </label>
      <button class="btn" type="button" disabled={disabled || !next} onClick={() => onMove(next)}>
        Move
      </button>
    </div>
  );
}

function PriorityEditor({ value, disabled, onSave }: { value: number | null; disabled: boolean; onSave: (p: number) => void }) {
  const [draft, setDraft] = useState(value == null ? "" : String(value));
  return (
    <section class="drawer-section">
      <h3>Priority</h3>
      <div class="drawer-row">
        <select
          class="input"
          aria-label="Priority"
          disabled={disabled}
          value={draft}
          onChange={(e) => setDraft((e.target as HTMLSelectElement).value)}
        >
          <option value="" disabled={value != null}>Not set</option>
          <option value="1">1 · high</option>
          <option value="2">2 · medium</option>
          <option value="3">3 · low</option>
        </select>
        <button
          class="btn"
          type="button"
          disabled={disabled || draft === "" || draft === String(value ?? "")}
          onClick={() => onSave(Number(draft))}
        >
          Set priority
        </button>
      </div>
    </section>
  );
}

function EditedMark() {
  return (
    <span class="edited-mark" title="You edited this field. Automation will not overwrite it.">
      edited
    </span>
  );
}

function DescriptionEditor({
  value,
  edited,
  disabled,
  onSave,
}: {
  value: string;
  edited: boolean;
  disabled: boolean;
  onSave: (text: string) => void;
}) {
  const [draft, setDraft] = useState(value);
  return (
    <section class="drawer-section">
      <h3>
        Description {edited && <EditedMark />}
      </h3>
      <textarea
        class="input textarea"
        rows={6}
        value={draft}
        onInput={(e) => setDraft((e.target as HTMLTextAreaElement).value)}
        aria-label="Description"
      />
      <div class="drawer-row end">
        <button
          class="btn"
          type="button"
          disabled={disabled || draft === value}
          onClick={() => onSave(draft)}
        >
          Save description
        </button>
      </div>
    </section>
  );
}

function NotesEditor({ value, disabled, onSave }: { value: string; disabled: boolean; onSave: (text: string) => void }) {
  const [draft, setDraft] = useState(value);
  return (
    <section class="drawer-section">
      <h3>Notes</h3>
      <textarea
        class="input textarea"
        rows={4}
        value={draft}
        onInput={(e) => setDraft((e.target as HTMLTextAreaElement).value)}
        aria-label="Notes"
      />
      <div class="drawer-row end">
        <button class="btn primary" type="button" disabled={disabled || draft === value} onClick={() => onSave(draft)}>
          Save notes
        </button>
      </div>
    </section>
  );
}
