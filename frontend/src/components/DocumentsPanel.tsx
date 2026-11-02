import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  type AiStatus,
  type DocumentKind,
  downloadUrl,
  draftDocument,
  getAiStatus,
  KIND_LABEL,
  latestByKind,
  listDocuments,
  saveAsIs,
  saveDocument,
  type TailoredDocument,
} from "../api/ai";
import { listPresets, type MissingField, type Preset } from "../api/presets";
import { matchText } from "../api/analysis";
import { diffCounts, diffLines } from "../domain/diff";
import { type Comparison, compare, comparisonLine } from "../domain/match";
import { placeholders } from "../domain/presets";
import { formatDate } from "../screens/columns";

interface Props {
  vacancyId: string;
  title: string;
  company: string;
  /** The chosen preset, shared with the analysis; null until the default one is known. */
  presetId: string | null;
  onPresetChange: (id: string) => void;
  /** Called after a version is saved, so the application package sees it. */
  onSaved?: (doc: TailoredDocument) => void;
}

interface Editing {
  kind: DocumentKind;
  content: string;
  origin: "ai" | "manual";
  provider?: string;
  model?: string;
  snapshotId?: string | null;
  /** The base draft an AI draft adapts, for the comparison. */
  baseDraft?: string;
}

/** A comparison being computed, computed, or failed. */
type Scored = { label: string; pending?: boolean; comparison?: Comparison; error?: string };

/** "Base 62% → Tailored 78% (+16)" with the newly covered and still missing requirements. */
function ScoreLine({ scored }: { scored: Scored | undefined }) {
  if (!scored) {
    return null;
  }
  if (scored.pending) {
    return <p class="muted small" role="status">Scoring the {scored.label.toLowerCase()} text…</p>;
  }
  if (scored.error || !scored.comparison) {
    return <p class="small warn-text">{scored.error}</p>;
  }
  const c = scored.comparison;
  return (
    <div class="comparison small" role="status">
      <strong class="num">{comparisonLine(c, scored.label)}</strong>
      {c.newlyCovered.length > 0 && <p>Newly covered: {c.newlyCovered.join("; ")}</p>}
      {c.stillMissing.length > 0 && <p>Still missing: {c.stillMissing.join("; ")}</p>}
      <p class="muted">Employer match: how well the text covers the vacancy's requirements, not a chance of being hired.</p>
    </div>
  );
}

const ORIGIN_LABEL: Record<TailoredDocument["origin"], string> = {
  ai: "AI adaptation, checked by you",
  manual: "written by you",
  base: "base draft as is",
};

/** What keeps the preset from generating this document, or an empty list. */
const missingFor = (preset: Preset | undefined, kind: DocumentKind): MissingField[] =>
  preset ? (kind === "resume" ? preset.readiness.resume : preset.readiness.coverLetter) : [];

/** Whether the preset's base draft of this kind can be used as it is (filled in, not the template). */
const baseUsable = (preset: Preset | undefined, kind: DocumentKind) =>
  !!preset &&
  (kind === "resume"
    ? !preset.baseResume.template && placeholders(preset.baseResume.text).length === 0
    : placeholders(preset.baseCoverLetter).length === 0);

/**
 * Resume and cover letter for this vacancy (T-083, T-094). Choose a preset, then for each document either use its
 * base draft as it is or let AI adapt the base draft to this vacancy. An AI draft opens in an editor next to the
 * comparison with the base draft; nothing is stored until "Save as version N". Every saved version stays.
 */
export function DocumentsPanel({ vacancyId, presetId, onPresetChange, onSaved }: Props) {
  const [docs, setDocs] = useState<TailoredDocument[]>([]);
  const [ai, setAi] = useState<AiStatus | null>(null);
  const [presets, setPresets] = useState<Preset[]>([]);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [showDiff, setShowDiff] = useState(true);
  const [instructions, setInstructions] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Base draft versus the AI draft or a saved version, per document, on the same checklist (T-096).
  const [scores, setScores] = useState<Partial<Record<DocumentKind, Scored>>>({});

  useEffect(() => {
    const controller = new AbortController();
    listDocuments(vacancyId, controller.signal).then(setDocs).catch(() => undefined);
    getAiStatus(controller.signal).then(setAi).catch(() => undefined);
    listPresets(controller.signal)
      .then((list) => {
        setPresets(list);
        const chosen = presetId ?? list.find((p) => p.isDefault)?.id ?? list[0]?.id;
        if (chosen && chosen !== presetId) {
          onPresetChange(chosen);
        }
      })
      .catch(() => undefined);
    return () => controller.abort();
  }, [vacancyId]);

  const latest = latestByKind(docs);
  const preset = presets.find((p) => p.id === presetId);

  const added = (doc: TailoredDocument) => {
    setDocs((list) => [doc, ...list]);
    onSaved?.(doc);
  };

  const act = async (key: string, action: () => Promise<void>, failure: string) => {
    setBusy(key);
    setError(null);
    try {
      await action();
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : failure);
    } finally {
      setBusy(null);
    }
  };

  /** Scores the base draft and the text on the vacancy's checklist (cached on the server) and keeps the comparison. */
  const score = async (kind: DocumentKind, base: string, text: string, label: string) => {
    if (!ai?.enabled) {
      return;
    }
    setScores((s) => ({ ...s, [kind]: { label, pending: true } }));
    try {
      const [before, after] = await Promise.all([matchText(vacancyId, base), matchText(vacancyId, text)]);
      setScores((s) => ({ ...s, [kind]: { label, comparison: compare(before, after) } }));
    } catch (e: unknown) {
      setScores((s) => ({ ...s, [kind]: { label, error: e instanceof ApiError ? e.message : "The match could not be computed." } }));
    }
  };

  const generate = (kind: DocumentKind) =>
    act(`draft-${kind}`, async () => {
      const d = await draftDocument(vacancyId, kind, presetId, instructions);
      setEditing({ kind, content: d.content, origin: "ai", provider: d.provider, model: d.model, snapshotId: d.snapshotId, baseDraft: d.baseDraft });
      setShowDiff(true);
      void score(kind, d.baseDraft, d.content, "Tailored");
    }, "The draft could not be generated.");

  const asIs = (kind: DocumentKind) => act(`as-is-${kind}`, async () => added(await saveAsIs(vacancyId, kind, presetId)), "The base draft could not be saved.");

  const save = () =>
    editing &&
    act("save", async () => {
      const saved = await saveDocument(vacancyId, editing);
      added(saved);
      setEditing(null);
      if (editing.baseDraft !== undefined) {
        void score(saved.kind, editing.baseDraft, saved.content, `Version ${saved.version}`);
      }
    }, "Saving failed. Your text is still in the editor.");

  const nextVersion = editing ? (latest[editing.kind]?.version ?? 0) + 1 : 1;
  const diff = editing?.baseDraft !== undefined ? diffLines(editing.baseDraft, editing.content) : null;
  const counts = diff ? diffCounts(diff) : null;

  return (
    <section class="drawer-section" aria-labelledby="docs-title">
      <h3 id="docs-title">Resume and cover letter for this vacancy</h3>
      <div class="drawer-row">
        <label class="small">
          Preset{" "}
          <select class="input" aria-label="Preset for the documents" value={presetId ?? ""} onChange={(e) => onPresetChange((e.target as HTMLSelectElement).value)}>
            {presets.map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
                {p.isDefault ? " (default)" : ""}
              </option>
            ))}
          </select>
        </label>
        <a class="small" href={`#/presets${presetId ? `?id=${encodeURIComponent(presetId)}` : ""}`}>
          Edit presets
        </a>
      </div>
      {ai && !ai.enabled && (
        <p class="muted small">
          AI is off{ai.configured ? "" : " (not configured)"}: <a href="#/settings">switch it on in Settings</a> to adapt the base
          drafts with Claude. Use as is works without AI.
        </p>
      )}
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}

      <div class="doc-cards">
        {(["resume", "cover_letter"] as DocumentKind[]).map((kind) => {
          const missing = missingFor(preset, kind);
          const canGenerate = !!ai?.enabled && missing.length === 0;
          const last = latest[kind];
          return (
            <div key={kind} class="doc-card" aria-label={KIND_LABEL[kind]} role="group">
              <div class="doc-head">
                <strong>{KIND_LABEL[kind]}</strong>
                <span class="muted small">
                  {last ? (
                    <>
                      {" "}
                      v{last.version} · {ORIGIN_LABEL[last.origin]} · {formatDate(last.createdAt)} ·{" "}
                      <a href={downloadUrl(last.id, "docx")} download>
                        DOCX
                      </a>{" "}
                      ·{" "}
                      <a href={downloadUrl(last.id, "md")} download>
                        MD
                      </a>
                    </>
                  ) : (
                    " none yet"
                  )}
                </span>
              </div>
              <div class="drawer-row">
                <button class="btn" type="button" disabled={busy !== null || !baseUsable(preset, kind)}
                  title={baseUsable(preset, kind) ? undefined : "Fill in the base draft on the Presets screen first"}
                  onClick={() => asIs(kind)}>
                  {busy === `as-is-${kind}` ? "Saving…" : "Use as is"}
                </button>
                <button class="btn primary" type="button" disabled={busy !== null || !canGenerate}
                  title={!ai?.enabled ? "Switch AI on in Settings first" : missing.length ? "Complete the preset first" : undefined}
                  onClick={() => generate(kind)}>
                  {busy === `draft-${kind}` ? "Generating…" : "Generate with AI"}
                </button>
              </div>
              <ScoreLine scored={scores[kind]} />
              {missing.length > 0 && (
                <p class="small warn-text">
                  Not ready for AI: {missing.map((m) => m.message).join(" ")}{" "}
                  <a href={`#/presets?id=${encodeURIComponent(preset!.id)}`}>Complete the preset</a>
                </p>
              )}
            </div>
          );
        })}
      </div>

      {ai?.enabled && (
        <label class="stack">
          <span class="small">Instructions for AI (optional)</span>
          <input class="input" value={instructions} maxLength={2000} placeholder="For example: stress the payments experience"
            onInput={(e) => setInstructions((e.target as HTMLInputElement).value)} />
        </label>
      )}

      {editing && (
        <div class="doc-editor stack">
          <p class="small">
            <strong>{KIND_LABEL[editing.kind]}</strong> —{" "}
            {editing.origin === "ai" ? "Claude's adaptation of your base draft: check every fact before saving." : "your text."} Nothing is
            stored until you save.
          </p>
          {diff && counts && (
            <div class="stack">
              <button class="link-button small" type="button" aria-expanded={showDiff} onClick={() => setShowDiff((s) => !s)}>
                Base vs tailored: {counts.added} lines added, {counts.removed} removed
              </button>
              {showDiff && (
                <ol class="diff" aria-label="Base draft compared with the tailored text">
                  {diff.map((line, i) => (
                    <li key={i} class={`diff-${line.type}`}>
                      <span class="diff-mark" aria-label={line.type === "added" ? "added" : line.type === "removed" ? "removed" : "unchanged"}>
                        {line.type === "added" ? "+" : line.type === "removed" ? "−" : " "}
                      </span>
                      {line.text || " "}
                    </li>
                  ))}
                </ol>
              )}
            </div>
          )}
          <textarea class="input textarea" rows={14} aria-label={`${KIND_LABEL[editing.kind]} text`} value={editing.content}
            onInput={(e) => setEditing({ ...editing, content: (e.target as HTMLTextAreaElement).value })} />
          <div class="drawer-row end">
            {ai?.enabled && editing.baseDraft !== undefined && (
              <button class="btn ghost" type="button" disabled={busy !== null}
                onClick={() => editing.baseDraft !== undefined && void score(editing.kind, editing.baseDraft, editing.content, "Edited")}>
                Score my edit
              </button>
            )}
            <button class="btn ghost" type="button" onClick={() => setEditing(null)}>
              Discard
            </button>
            <button class="btn primary" type="button" disabled={busy !== null || !editing.content.trim()} onClick={save}>
              {busy === "save" ? "Saving…" : `Save as version ${nextVersion}`}
            </button>
          </div>
        </div>
      )}

      {docs.length > 0 && (
        <details>
          <summary class="small">All versions ({docs.length})</summary>
          <ul class="history">
            {docs.map((d) => (
              <li key={d.id}>
                {KIND_LABEL[d.kind]} v{d.version} · {d.origin === "ai" ? `AI (${d.model ?? d.provider})` : ORIGIN_LABEL[d.origin]} ·{" "}
                <span class="num">{formatDate(d.createdAt)}</span> ·{" "}
                <a href={downloadUrl(d.id, "docx")} download>
                  DOCX
                </a>{" "}
                ·{" "}
                <button class="link-button" type="button"
                  onClick={() => setEditing({ kind: d.kind, content: d.content, origin: "manual", snapshotId: d.snapshotId })}>
                  Edit a copy
                </button>
              </li>
            ))}
          </ul>
        </details>
      )}
    </section>
  );
}
