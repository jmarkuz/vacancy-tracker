import { useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
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
import { bodyParagraphs, digestHeading, jobMeta, MAIL_REST_NOTE, safeHref, textWasCut } from "../domain/mail";
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

/** The start of a mail's text as paragraphs; a long one is folded to a few lines with a button to unfold it. */
function MailBody({ text }: { text: string }) {
  const [open, setOpen] = useState(false);
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
        <button class="mail-more" type="button" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
          {open ? "Show less" : "Show more"}
          <Icon name="chevron-down" />
        </button>
      )}
    </div>
  );
}

/** Job mail with its category, the application it belongs to and the suggested status, to accept or dismiss. */
export function MailList({ items, showVacancyLink = false, onChanged, selected, onToggle }: Props) {
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
      <ul class="mail-list">
        {items.map((m) => (
          <li key={m.id} class={`mail-item${m.suggestionState === "pending" ? " pending" : ""}`}>
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
            {isDigest(m) ? <DigestJobs mailId={m.id} jobs={m.jobs} /> : m.snippet && <MailBody text={m.snippet} />}
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
