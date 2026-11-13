import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { getVacancy, patchVacancy } from "../api/vacancies";
import {
  acceptSuggestion,
  addDigestJob,
  type ApplicationOption,
  categoryLabel,
  dismissSuggestion,
  isDigest,
  listApplications,
  type Mail,
  relinkMail,
  suggestionText,
} from "../api/mail";
import { buildIcs, downloadFile, eventInstant, formatEventTime, icsFileName } from "../domain/calendar";
import { bodyParagraphs, digestHeading, jobMeta, MAIL_REST_NOTE, safeHref, textWasCut } from "../domain/mail";
import { mailKeyFromEvent } from "../domain/mailKeys";
import { moveSelection, selectionAfterRemoval } from "../domain/triage";
import { formatDate } from "../screens/columns";
import { Icon } from "./Icon";

interface Props {
  items: Mail[];
  /** Shows a link to the vacancy each mail belongs to (on the Mail screen, not inside the drawer). */
  showVacancyLink?: boolean;
  /** Called after a suggestion was accepted or dismissed. */
  onChanged: (message: string) => void;
  /** With these, each mail gets a checkbox (the Mail screen deletes the chosen ones). */
  selected?: ReadonlySet<string>;
  onToggle?: (id: string) => void;
  /** Turns on the keyboard (J/K, X, A, D, L, O, Enter): the Mail screen does, the drawer does not. */
  keyboard?: boolean;
}

/**
 * The vacancies of a digest mail, one row each: what the job is, where, a link to its page and its place in the
 * Pipeline. A posting is added by its link (that is how the server finds out it is there already), so only a posting
 * with a web link and a company (a vacancy needs one) gets the button.
 */
function DigestJobs({ mailId, jobs }: { mailId: string; jobs: Mail["jobs"] }) {
  const [added, setAdded] = useState<Record<number, string>>({});
  const [adding, setAdding] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  // A second click before the first answer arrives must not send a second request.
  const inFlight = useRef(new Set<number>());

  const add = async (index: number) => {
    if (inFlight.current.has(index)) {
      return;
    }
    inFlight.current.add(index);
    setAdding(index);
    setError(null);
    try {
      const r = await addDigestJob(mailId, index);
      setAdded((prev) => ({ ...prev, [index]: r.vacancyId }));
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "Could not add the vacancy to the Pipeline.");
    } finally {
      inFlight.current.delete(index);
      setAdding(null);
    }
  };

  return (
    <div class="digest">
      <p class="digest-heading">{digestHeading(jobs.length)}</p>
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      <ul class="digest-list">
        {jobs.map((job, i) => {
          const href = safeHref(job.url);
          const meta = jobMeta(job);
          const vacancyId = added[i] ?? job.vacancyId ?? null;
          return (
            <li key={`${job.url ?? job.title}-${i}`} class="digest-job">
              <div class="digest-text">
                <span class="digest-title">{job.title}</span>
                {meta && <span class="digest-meta">{meta}</span>}
              </div>
              <div class="digest-actions">
                {href && (
                  <a class="digest-link" href={href} target="_blank" rel="noopener noreferrer" aria-label={`View ${job.title} (opens a new tab)`}>
                    View
                    <Icon name="external" />
                  </a>
                )}
                {vacancyId ? (
                  <>
                    <span class="digest-state">In Pipeline</span>
                    <a class="digest-link" href={`#/pipeline?open=${encodeURIComponent(vacancyId)}`} aria-label={`Open ${job.title} in the Pipeline`}>
                      Open
                    </a>
                  </>
                ) : (
                  href &&
                  job.company && (
                    <button
                      class="btn digest-add"
                      type="button"
                      disabled={adding === i}
                      aria-label={`Add ${job.title} to Pipeline`}
                      onClick={() => void add(i)}
                    >
                      <Icon name="plus" />
                      {adding === i ? "Adding…" : "Add to Pipeline"}
                    </button>
                  )
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/**
 * The date and time a mail names (an interview, a call, a test-task deadline): shown as a line with two actions. "Set as
 * next action" writes it to the linked application; "Add to calendar" downloads an .ics file made in the browser.
 */
function EventLine({ mail }: { mail: Mail }) {
  const event = mail.event;
  const [state, setState] = useState<"idle" | "saving" | "done">("idle");
  const [error, setError] = useState<string | null>(null);
  const when = event ? formatEventTime(event) : null;
  if (!event || !when) {
    return null;
  }

  const setNextAction = async () => {
    const at = eventInstant(event);
    if (!mail.vacancyId || !at || state === "saving") {
      return;
    }
    setState("saving");
    setError(null);
    try {
      await patchVacancy(mail.vacancyId, {
        nextActionAt: at,
        nextActionNote: `${mail.category === "test_task" ? "Test task" : "Interview"} — from mail`,
      });
      setState("done");
    } catch (e: unknown) {
      setState("idle");
      setError(e instanceof ApiError ? e.message : "Could not set the next action.");
    }
  };

  const addToCalendar = async () => {
    setError(null);
    // The vacancy's title makes the better summary; the mail's subject does when it cannot be read.
    let title = mail.subject ?? "(no subject)";
    if (mail.vacancyId) {
      try {
        title = (await getVacancy(mail.vacancyId)).title || title;
      } catch {
        /* keep the subject */
      }
    }
    const ics = buildIcs({
      uid: `${mail.id}@vacancy-tracker`,
      summary: `Interview: ${title}`,
      description: `From: ${mail.senderName ? `${mail.senderName} <${mail.sender}>` : (mail.sender ?? "unknown")}\nSubject: ${mail.subject ?? "(no subject)"}`,
      event,
      now: new Date(),
    });
    if (ics) {
      downloadFile(icsFileName(event), ics);
    }
  };

  return (
    <div class="mail-event">
      <span class="mail-event-when" title={event.text}>
        <Icon name="calendar" />
        <span class="num">{when}</span>
        {!event.zoneKnown && <span class="muted"> (local time)</span>}
      </span>
      {mail.vacancyId &&
        (state === "done" ? (
          <span class="mail-event-done" role="status">
            <Icon name="check" />
            Next action set
          </span>
        ) : (
          <button class="btn" type="button" disabled={state === "saving"} onClick={() => void setNextAction()}>
            {state === "saving" ? "Setting…" : "Set as next action"}
          </button>
        ))}
      <button class="btn" type="button" onClick={() => void addToCalendar()}>
        <Icon name="calendar" />
        Add to calendar
      </button>
      {error && (
        <span class="mail-event-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}

/** The start of a mail's text as paragraphs; a long one is folded to a few lines with a button to unfold it. */
function MailBody({ text, open, onToggle }: { text: string; open: boolean; onToggle: () => void }) {
  const { paragraphs, long } = bodyParagraphs(text);
  return (
    <div class="mail-body">
      <div class={`mail-text${long && !open ? " folded" : ""}`}>
        {paragraphs.map((p, i) => (
          <p key={i}>{p}</p>
        ))}
      </div>
      {open && textWasCut(text) && <p class="mail-rest muted">{MAIL_REST_NOTE}</p>}
      {long && (
        <button class="mail-more" type="button" aria-expanded={open} onClick={onToggle}>
          {open ? "Show less" : "Show more"}
          <Icon name="chevron-down" />
        </button>
      )}
    </div>
  );
}

/** Job mail with its category, the application it belongs to and the suggested status, to accept or dismiss. */
export function MailList({ items, showVacancyLink = false, onChanged, selected, onToggle, keyboard = false }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [linking, setLinking] = useState<string | null>(null);
  const [options, setOptions] = useState<ApplicationOption[] | null>(null);

  // The applications change (a status moves, a vacancy is dismissed), so the list is read again each time linking
  // opens. A slow answer for an earlier opening must not replace the newer one.
  const latestRequest = useRef(0);
  const startLinking = async (m: Mail) => {
    const request = ++latestRequest.current;
    setLinking(m.id);
    setError(null);
    setOptions(null);
    try {
      const loaded = await listApplications();
      if (request === latestRequest.current) {
        setOptions(loaded);
      }
    } catch {
      if (request === latestRequest.current) {
        setError("Could not load your applications.");
        setLinking(null);
      }
    }
  };

  const link = async (m: Mail, vacancyId: string | null) => {
    setBusy(m.id);
    setError(null);
    try {
      await relinkMail(m.id, vacancyId);
      setLinking(null);
      onChanged(vacancyId ? "Mail linked to the application." : "Mail unlinked.");
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "That did not work.");
    } finally {
      setBusy(null);
    }
  };

  // The mails whose folded text is unfolded (kept here, so the Enter key can do what the button does).
  const [unfolded, setUnfolded] = useState<ReadonlySet<string>>(new Set());
  const toggleUnfolded = (id: string) =>
    setUnfolded((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) {
        next.add(id);
      }
      return next;
    });

  const act = async (m: Mail, accept: boolean) => {
    setBusy(m.id);
    setError(null);
    try {
      if (accept) {
        const r = await acceptSuggestion(m.id);
        onChanged(`Moved to ${r.vacancyStatus}.`);
      } else {
        await dismissSuggestion(m.id);
        onChanged("Suggestion dismissed; the status stays as it is.");
      }
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "That did not work.");
    } finally {
      setBusy(null);
    }
  };

  // The current mail of the keyboard: remembered by id, so it stays put when the list changes. When it is gone (deleted
  // or accepted out of a filtered list), the cursor stays at the same place.
  const [currentId, setCurrentId] = useState<string | null>(null);
  const lastIndex = useRef(-1);
  const found = items.findIndex((m) => m.id === currentId);
  const current = currentId === null ? -1 : found >= 0 ? found : selectionAfterRemoval(lastIndex.current, items.length);
  lastIndex.current = current;
  const listRef = useRef<HTMLUListElement>(null);
  const focusSelect = useRef(false);

  // Registered once and reading a ref, so a key pressed right after another sees what that one did.
  const live = useRef({ items, current, busy, linking, selected, onToggle, act, startLinking, toggleUnfolded, unfolded, keyboard });
  live.current = { items, current, busy, linking, selected, onToggle, act, startLinking, toggleUnfolded, unfolded, keyboard };

  const moveTo = (index: number, focus: boolean) => {
    const m = live.current.items[index];
    if (!m) {
      return;
    }
    setCurrentId(m.id);
    const card = listRef.current?.children[index] as HTMLElement | undefined;
    if (card) {
      // The focus follows the cursor, so a screen reader reads the new mail.
      if (focus) {
        card.focus({ preventScroll: true });
      }
      const calm = typeof window.matchMedia === "function" && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
      card.scrollIntoView?.({ block: "nearest", behavior: calm ? "auto" : "smooth" });
    }
  };

  useEffect(() => {
    if (!keyboard) {
      return undefined;
    }
    const onKey = (e: KeyboardEvent) => {
      const action = mailKeyFromEvent(e);
      const { items: list, current: at, busy: working, linking: openFor, selected: chosen, onToggle: toggleChoice } = live.current;
      if (!action || action === "help" || action === "escape" || list.length === 0) {
        return;
      }
      if (action === "next" || action === "previous") {
        e.preventDefault();
        moveTo(moveSelection(at, action === "next" ? 1 : -1, list.length), true);
        return;
      }
      if (action === "first" || action === "last") {
        e.preventDefault();
        moveTo(action === "first" ? 0 : list.length - 1, true);
        return;
      }
      const m = list[at];
      if (!m) {
        return;
      }
      e.preventDefault();
      if (action === "toggle-select") {
        if (chosen && toggleChoice) {
          toggleChoice(m.id);
        }
      } else if (action === "accept" || action === "dismiss") {
        // Only a mail with a suggestion to decide, and not while another request runs.
        if (suggestionText(m) && working === null && !e.repeat) {
          void live.current.act(m, action === "accept");
        }
      } else if (action === "link") {
        if (working === null && openFor !== m.id && !e.repeat) {
          focusSelect.current = true;
          void live.current.startLinking(m);
        }
      } else if (action === "open-application") {
        if (m.vacancyId) {
          window.location.hash = `#/pipeline?open=${encodeURIComponent(m.vacancyId)}`;
        }
      } else if (action === "toggle-more") {
        if (!isDigest(m) && m.snippet && bodyParagraphs(m.snippet).long && !e.repeat) {
          live.current.toggleUnfolded(m.id);
        }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [keyboard]);

  // After L the picker opens and its list loads; the focus goes to it so the choice can be made with the keys.
  useEffect(() => {
    if (focusSelect.current && options !== null) {
      focusSelect.current = false;
      listRef.current?.querySelector<HTMLSelectElement>("select")?.focus();
    }
  }, [options]);

  if (items.length === 0) {
    return <p class="muted">No job mail yet.</p>;
  }
  return (
    <>
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      <ul class="mail-list" ref={listRef}>
        {items.map((m, i) => (
          <li
            key={m.id}
            id={`mail-${m.id}`}
            class={`mail-item${m.suggestionState === "pending" ? " pending" : ""}${keyboard && i === current ? " is-current" : ""}`}
            // One tab stop for the list (roving tabindex): the current mail, or the first one before any is chosen.
            tabIndex={keyboard ? (i === (current < 0 ? 0 : current) ? 0 : -1) : undefined}
            aria-current={keyboard && i === current ? "true" : undefined}
            // Tabbing to a button of a mail, or a click on it, makes it the current one.
            onFocusIn={keyboard ? () => setCurrentId(m.id) : undefined}
          >
            <div class="mail-head">
              {selected && onToggle && (
                <input
                  type="checkbox"
                  aria-label={`Select ${m.subject ?? "mail"}`}
                  checked={selected.has(m.id)}
                  onChange={() => onToggle(m.id)}
                />
              )}
              <span class={`mail-category cat-${isDigest(m) ? "digest" : m.category}`}>{categoryLabel(m)}</span>
              <span class="mail-date num">{formatDate(m.receivedAt)}</span>
            </div>
            <strong class="mail-subject">{m.subject ?? "(no subject)"}</strong>
            <div class="mail-from">
              {m.senderName ? `${m.senderName} · ` : ""}
              {m.sender}
            </div>
            <EventLine mail={m} />
            {isDigest(m) ? <DigestJobs mailId={m.id} jobs={m.jobs} /> : m.snippet && <MailBody text={m.snippet} open={unfolded.has(m.id)} onToggle={() => toggleUnfolded(m.id)} />}
            {m.vacancyId ? (
              <p class="mail-link small">
                {showVacancyLink && (
                  <>
                    <a href={`#/pipeline?open=${encodeURIComponent(m.vacancyId)}`}>Open the application</a> ·{" "}
                  </>
                )}
                <span class="muted">{m.linkReason}</span>
              </p>
            ) : (
              <p class="mail-link small muted">Not linked to an application.</p>
            )}
            {linking === m.id ? (
              <div class="mail-actions">
                <select
                  class="input"
                  aria-label="Application for this mail"
                  disabled={busy !== null || options === null}
                  value=""
                  onKeyDown={(e) => {
                    if (e.key === "Escape") {
                      e.stopPropagation();
                      setLinking(null);
                      document.getElementById(`mail-${m.id}`)?.focus({ preventScroll: true });
                    }
                  }}
                  onChange={(e) => {
                    const v = (e.currentTarget as HTMLSelectElement).value;
                    if (v) {
                      void link(m, v);
                    }
                  }}
                >
                  <option value="">{options === null ? "Loading…" : options.length === 0 ? "No applications yet" : "Choose an application…"}</option>
                  {(options ?? []).filter((o) => o.id !== m.vacancyId).map((o) => (
                    <option key={o.id} value={o.id}>
                      {o.title}
                      {o.company ? ` · ${o.company}` : ""} ({o.status})
                    </option>
                  ))}
                </select>
                <button class="btn" type="button" onClick={() => setLinking(null)}>
                  Cancel
                </button>
              </div>
            ) : (
              <div class="mail-actions">
                <button class="btn" type="button" disabled={busy !== null} onClick={() => void startLinking(m)}>
                  <Icon name="link" />
                  {m.vacancyId ? "Change application" : "Link to an application"}
                </button>
                {m.vacancyId && (
                  <button class="btn" type="button" disabled={busy !== null} onClick={() => void link(m, null)}>
                    Unlink
                  </button>
                )}
              </div>
            )}
            {suggestionText(m) && (
              <div class="mail-suggestion">
                <span class="mail-suggestion-text">{suggestionText(m)}</span>
                <button class="btn primary" data-role="green" type="button" disabled={busy !== null} onClick={() => act(m, true)}>
                  <Icon name="check" />
                  Accept
                </button>
                <button class="btn" type="button" disabled={busy !== null} onClick={() => act(m, false)}>
                  Dismiss
                </button>
              </div>
            )}
            {m.suggestionState === "accepted" && <p class="small muted">Status changed to {m.suggestedStatus}.</p>}
          </li>
        ))}
      </ul>
    </>
  );
}
