import { useEffect, useRef, useState } from "preact/hooks";
import { KIND_LABEL, latestByKind, listDocuments, type TailoredDocument } from "../api/ai";
import type { ResumeFit } from "../api/resume";
import type { Vacancy } from "../api/types";
import { skillCount } from "../domain/fit";
import { safeLink } from "../domain/links";
import { nextTab } from "../domain/tabs";
import { formatDate, formatSalary } from "../screens/columns";

type Tab = "conditions" | "match" | "documents";
const TABS: { id: Tab; label: string; role: "blue" | "purple" | "green" }[] = [
  { id: "conditions", label: "Conditions", role: "blue" },
  { id: "match", label: "Match", role: "purple" },
  { id: "documents", label: "Documents", role: "green" },
];

interface Props {
  vacancy: Vacancy;
  fit: ResumeFit | undefined;
  /** A save or skip is on its way: the buttons wait, so a second click cannot send it twice. */
  busy: boolean;
  onSave: () => void;
  onSkip: () => void;
  onDetails: () => void;
}

/**
 * The selected vacancy next to the Inbox list (Beacon, T-101): no overlay, the list stays usable. "Save vacancy" is
 * always in the same place; the tabs show the conditions, the match with the resume and the documents. The tabs
 * follow the WAI-ARIA pattern: arrows, Home and End move between them, Tab goes into the content.
 */
export function InboxInspector({ vacancy, fit, busy, onSave, onSkip, onDetails }: Props) {
  const [tab, setTab] = useState<Tab>("conditions");
  const [docs, setDocs] = useState<TailoredDocument[] | null>(null);
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);

  useEffect(() => {
    if (tab !== "documents") {
      return;
    }
    const controller = new AbortController();
    setDocs(null);
    listDocuments(vacancy.id, controller.signal).then(setDocs).catch(() => setDocs([]));
    return () => controller.abort();
  }, [vacancy.id, tab]);

  const onTabKey = (e: KeyboardEvent, index: number) => {
    const next = nextTab(e.key, index, TABS.length);
    if (next !== null) {
      e.preventDefault();
      setTab(TABS[next].id);
      tabRefs.current[next]?.focus();
    }
  };

  const count = fit ? skillCount(fit) : null;
  const link = safeLink(vacancy.url);
  const latest = docs ? latestByKind(docs) : {};

  return (
    <aside class="inspector panel" aria-label={`Selected vacancy: ${vacancy.title}`}>
      <p class="eyebrow" data-role="blue">New vacancy</p>
      <h2 class="inspector-title">{vacancy.title}</h2>
      <p class="muted inspector-company">{vacancy.company}</p>
      <button class="btn primary block" type="button" data-role="green" disabled={busy} aria-busy={busy} onClick={onSave}>
        {busy ? "Saving…" : "Save vacancy"}
      </button>

      <div class="tabs" role="tablist" aria-label="About this vacancy">
        {TABS.map((t, i) => (
          <button
            key={t.id}
            ref={(el) => {
              tabRefs.current[i] = el;
            }}
            type="button"
            role="tab"
            id={`inspector-tab-${t.id}`}
            aria-controls={`inspector-panel-${t.id}`}
            aria-selected={tab === t.id}
            tabIndex={tab === t.id ? 0 : -1}
            class="tab"
            data-role={t.role}
            onClick={() => setTab(t.id)}
            onKeyDown={(e) => onTabKey(e, i)}
          >
            {t.label}
          </button>
        ))}
      </div>

      <div class="tab-panel" role="tabpanel" id={`inspector-panel-${tab}`} aria-labelledby={`inspector-tab-${tab}`}>
        {tab === "conditions" && (
          <dl class="facts">
            <dt>Location</dt>
            <dd>{vacancy.location ?? "Not specified"}</dd>
            <dt>Work mode</dt>
            <dd>{vacancy.workMode ?? "Not specified"}</dd>
            <dt>Salary</dt>
            <dd class="num">{formatSalary(vacancy)}</dd>
            <dt>Visa sponsorship</dt>
            <dd>{vacancy.visaSponsorship === true ? "Offered" : vacancy.visaSponsorship === false ? "Not offered" : "Not stated"}</dd>
            <dt>Last seen</dt>
            <dd class="num">{formatDate(vacancy.lastSeenAt)}</dd>
            <dt>Source</dt>
            <dd>
              {link ? (
                <a href={link} target="_blank" rel="noopener noreferrer">
                  Open posting
                </a>
              ) : (
                "Not specified"
              )}
            </dd>
          </dl>
        )}
        {tab === "match" && (
          <div class="stack">
            {!fit && (
              <p class="muted">
                <a href="#/resume">Upload your resume</a> to see which skills of this vacancy you have.
              </p>
            )}
            {fit && (
              <div class="metric-callout" data-role="purple">
                <strong class="metric-value num">{count ? `${count.matched} of ${count.total}` : "Not enough data"}</strong>
                <span>{count ? "skills of this vacancy are in your resume" : "the vacancy names no skill to compare"}</span>
              </div>
            )}
            {fit && fit.matched.length > 0 && (
              <p>
                <strong>You have:</strong> {fit.matched.join(", ")}
              </p>
            )}
            {fit && fit.missing.length > 0 && (
              <p>
                <strong>Not in your resume:</strong> {fit.missing.join(", ")}
              </p>
            )}
            {fit && <p class="muted small">A quick estimate from your resume keywords, not a chance of being hired.</p>}
          </div>
        )}
        {tab === "documents" && (
          <div class="stack">
            {docs === null && <p class="muted" role="status">Loading…</p>}
            {docs !== null && (
              <ul class="plain-list">
                {(["resume", "cover_letter"] as const).map((kind) => (
                  <li key={kind}>
                    <strong>{KIND_LABEL[kind]}:</strong>{" "}
                    {latest[kind] ? `version ${latest[kind]!.version}, ${formatDate(latest[kind]!.createdAt)}` : "none yet"}
                  </li>
                ))}
              </ul>
            )}
            <p class="muted small">Adapt the resume and cover letter, and prepare the application, in the full details.</p>
          </div>
        )}
      </div>

      <div class="drawer-row">
        <button class="btn" type="button" data-role="blue" disabled={busy} onClick={onSkip}>
          Skip
        </button>
        <button class="btn" type="button" data-role="blue" onClick={onDetails}>
          Full details
        </button>
      </div>
    </aside>
  );
}
