import { useRef, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  acceptSuggestion,
  type ApplicationOption,
  CATEGORY_LABEL,
  dismissSuggestion,
  listApplications,
  type Mail,
  relinkMail,
  suggestionText,
} from "../api/mail";
import { formatDate } from "../screens/columns";

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
              <span class={`mail-category cat-${m.category}`}>{CATEGORY_LABEL[m.category] ?? m.category}</span>
              <strong class="mail-subject">{m.subject ?? "(no subject)"}</strong>
              <span class="muted small num">{formatDate(m.receivedAt)}</span>
            </div>
            <div class="muted small">
              {m.senderName ? `${m.senderName} · ` : ""}
              {m.sender}
            </div>
            {m.snippet && <p class="mail-snippet">{m.snippet}</p>}
            {m.vacancyId ? (
              <p class="small">
                {showVacancyLink && (
                  <>
                    <a href={`#/pipeline?open=${encodeURIComponent(m.vacancyId)}`}>Open the application</a> ·{" "}
                  </>
                )}
                <span class="muted">{m.linkReason}</span>
              </p>
            ) : (
              <p class="small muted">Not linked to an application.</p>
            )}
            {linking === m.id ? (
              <div class="drawer-row">
                <select
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
                <button class="btn ghost" type="button" onClick={() => setLinking(null)}>
                  Cancel
                </button>
              </div>
            ) : (
              <p class="small">
                <button class="btn ghost" type="button" disabled={busy !== null} onClick={() => void startLinking(m)}>
                  {m.vacancyId ? "Change application" : "Link to an application"}
                </button>
                {m.vacancyId && (
                  <button class="btn ghost" type="button" disabled={busy !== null} onClick={() => void link(m, null)}>
                    Unlink
                  </button>
                )}
              </p>
            )}
            {suggestionText(m) && (
              <div class="drawer-row">
                <span>{suggestionText(m)}</span>
                <button class="btn primary" data-role="green" type="button" disabled={busy !== null} onClick={() => act(m, true)}>
                  Accept
                </button>
                <button class="btn ghost" type="button" disabled={busy !== null} onClick={() => act(m, false)}>
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
