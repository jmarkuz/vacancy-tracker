import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import {
  type AiStatus,
  coverLetterTemplate,
  type DocumentKind,
  downloadUrl,
  draftDocument,
  getAiStatus,
  KIND_LABEL,
  latestByKind,
  listDocuments,
  saveDocument,
  type TailoredDocument,
} from "../api/ai";
import { getResume } from "../api/resume";
import { formatDate } from "../screens/columns";

interface Props {
  vacancyId: string;
  title: string;
  company: string;
  /** Called after a version is saved, so the application package sees it. */
  onSaved?: (doc: TailoredDocument) => void;
}

interface Editing {
  kind: DocumentKind;
  content: string;
  origin: "ai" | "manual";
  provider?: string;
  model?: string;
}

/**
 * Tailored resume and cover letter for this vacancy (T-083). A draft (from Claude, or from the resume or a template)
 * opens in an editor; nothing is stored until "Save as version N". Every saved version stays and can be downloaded.
 */
export function DocumentsPanel({ vacancyId, title, company, onSaved }: Props) {
  const [docs, setDocs] = useState<TailoredDocument[]>([]);
  const [ai, setAi] = useState<AiStatus | null>(null);
  const [editing, setEditing] = useState<Editing | null>(null);
  const [instructions, setInstructions] = useState("");
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    listDocuments(vacancyId, controller.signal).then(setDocs).catch(() => undefined);
    getAiStatus(controller.signal).then(setAi).catch(() => undefined);
    return () => controller.abort();
  }, [vacancyId]);

  const latest = latestByKind(docs);

  const generate = async (kind: DocumentKind) => {
    setBusy(`draft-${kind}`);
    setError(null);
    try {
      const d = await draftDocument(vacancyId, kind, instructions);
      setEditing({ kind, content: d.content, origin: "ai", provider: d.provider, model: d.model });
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "The draft could not be generated.");
    } finally {
      setBusy(null);
    }
  };

  const startByHand = async (kind: DocumentKind) => {
    setError(null);
    if (kind === "cover_letter") {
      setEditing({ kind, content: latest.cover_letter?.content ?? coverLetterTemplate(title, company), origin: "manual" });
      return;
    }
    const base = latest.resume?.content ?? (await getResume().catch(() => null))?.text;
    if (!base) {
      setError("Upload your resume first, on the Resume screen.");
      return;
    }
    setEditing({ kind, content: base, origin: "manual" });
  };

  const save = async () => {
    if (!editing) {
      return;
    }
    setBusy("save");
    setError(null);
    try {
      const saved = await saveDocument(vacancyId, editing);
      setDocs((list) => [saved, ...list]);
      setEditing(null);
      onSaved?.(saved);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "Saving failed. Your text is still in the editor.");
    } finally {
      setBusy(null);
    }
  };

  const nextVersion = editing ? (latest[editing.kind]?.version ?? 0) + 1 : 1;

  return (
    <section class="drawer-section" aria-labelledby="docs-title">
      <h3 id="docs-title">Resume and cover letter for this vacancy</h3>
      {ai && !ai.enabled && (
        <p class="muted small">
          AI is off{ai.configured ? "" : " (not configured)"}: <a href="#/settings">switch it on in Settings</a> to generate
          drafts with Claude, or write them here by hand.
        </p>
      )}
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}

      {(["resume", "cover_letter"] as DocumentKind[]).map((kind) => (
        <div key={kind} class="doc-row">
          <div class="doc-head">
            <strong>{KIND_LABEL[kind]}</strong>
            {latest[kind] ? (
              <span class="muted small">
                {" "}
                v{latest[kind]!.version} · {latest[kind]!.origin === "ai" ? "AI draft, edited by you" : "written by you"} ·{" "}
                {formatDate(latest[kind]!.createdAt)} ·{" "}
                <a href={downloadUrl(latest[kind]!.id, "docx")} download>
                  DOCX
                </a>{" "}
                ·{" "}
                <a href={downloadUrl(latest[kind]!.id, "md")} download>
                  MD
                </a>
              </span>
            ) : (
              <span class="muted small"> none yet</span>
            )}
          </div>
          <div class="drawer-row">
            <button
              class="btn"
              type="button"
              disabled={!ai?.enabled || busy !== null}
              title={ai?.enabled ? undefined : "Switch AI on in Settings first"}
              onClick={() => generate(kind)}
            >
              {busy === `draft-${kind}` ? "Generating…" : `Generate ${KIND_LABEL[kind].toLowerCase()} with AI`}
            </button>
            <button class="btn ghost" type="button" disabled={busy !== null} onClick={() => startByHand(kind)}>
              {kind === "resume" ? "Start from my resume" : latest.cover_letter ? "Edit the last version" : "Start from a template"}
            </button>
          </div>
        </div>
      ))}

      {ai?.enabled && (
        <label class="stack">
          <span class="small">Instructions for the AI (optional)</span>
          <input
            class="input"
            value={instructions}
            maxLength={2000}
            placeholder="For example: write it in English, stress the payments experience"
            onInput={(e) => setInstructions((e.target as HTMLInputElement).value)}
          />
        </label>
      )}

      {editing && (
        <div class="doc-editor stack">
          <p class="small">
            <strong>{KIND_LABEL[editing.kind]}</strong> — {editing.origin === "ai" ? "a draft from Claude: check every fact before saving." : "your text."}{" "}
            Nothing is stored until you save.
          </p>
          <textarea
            class="input textarea"
            rows={14}
            aria-label={`${KIND_LABEL[editing.kind]} text`}
            value={editing.content}
            onInput={(e) => setEditing({ ...editing, content: (e.target as HTMLTextAreaElement).value })}
          />
          <div class="drawer-row end">
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
                {KIND_LABEL[d.kind]} v{d.version} · {d.origin === "ai" ? `AI (${d.model ?? d.provider})` : "by hand"} ·{" "}
                <span class="num">{formatDate(d.createdAt)}</span> ·{" "}
                <a href={downloadUrl(d.id, "docx")} download>
                  DOCX
                </a>{" "}
                ·{" "}
                <button class="link-button" type="button" onClick={() => setEditing({ kind: d.kind, content: d.content, origin: "manual" })}>
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
