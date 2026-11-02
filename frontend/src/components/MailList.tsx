import { useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { acceptSuggestion, CATEGORY_LABEL, dismissSuggestion, type Mail, suggestionText } from "../api/mail";
import { formatDate } from "../screens/columns";

interface Props {
  items: Mail[];
  /** Shows a link to the vacancy each mail belongs to (on the Mail screen, not inside the drawer). */
  showVacancyLink?: boolean;
  /** Called after a suggestion was accepted or dismissed. */
  onChanged: (message: string) => void;
}

/** Job mail with its category, the application it belongs to and the suggested status, to accept or dismiss. */
export function MailList({ items, showVacancyLink = false, onChanged }: Props) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

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
            {suggestionText(m) && (
              <div class="drawer-row">
                <span>{suggestionText(m)}</span>
                <button class="btn primary" type="button" disabled={busy !== null} onClick={() => act(m, true)}>
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
