import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { deleteResume, getResume, pasteResume, type Resume, saveKeywords, uploadResume } from "../api/resume";
import { parseKeywords } from "../domain/fit";
import { formatDate } from "./columns";

/**
 * The resume (T-080): upload a PDF, DOCX, TXT or MD file, or paste the text. Keywords are proposed from the text and
 * edited here; they drive the fit score of every vacancy (T-081). Nothing here is sent anywhere: AI is a separate,
 * explicit opt-in (Settings).
 */
export function ResumeScreen() {
  const [resume, setResume] = useState<Resume | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [pasting, setPasting] = useState(false);
  const [pasteText, setPasteText] = useState("");
  const [keywords, setKeywords] = useState<string[]>([]);
  const [newKeyword, setNewKeyword] = useState("");
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    getResume(controller.signal)
      .then((r) => {
        setResume(r);
        setKeywords(r?.keywords ?? []);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : "Could not load the resume.");
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, []);

  const run = async (action: () => Promise<Resume | void>, done: string) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const r = await action();
      if (r) {
        setResume(r);
        setKeywords(r.keywords);
      }
      setNotice(done);
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "That did not work. Nothing was changed.");
    } finally {
      setBusy(false);
    }
  };

  const keywordsChanged = resume !== null && keywords.join("\u0000") !== resume.keywords.join("\u0000");

  return (
    <section aria-labelledby="resume-title" class="stack">
      <div class="toolbar">
        <h1 id="resume-title">Resume</h1>
        <span class="grow" />
        {resume && (
          <span class="muted small">
            {resume.name} · revision <span class="num">{resume.revision}</span> · updated {formatDate(resume.updatedAt)}
          </span>
        )}
      </div>

      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div class="notice" role="status">
          {notice}
        </div>
      )}
      {loading && <p class="muted" role="status">Loading…</p>}

      <div class="panel">
        <h2>{resume ? "Replace the resume" : "Upload your resume"}</h2>
        <p class="muted">
          PDF, DOCX, TXT or Markdown, up to 5 MB. The text stays in your database. Every vacancy gets a fit score from
          it, with the reasons. It is also the base resume of your default <a href="#/presets">preset</a>: changing it
          in either place changes the same resume.
        </p>
        <div class="drawer-row">
          <label class="btn primary file-button" data-role="purple">
            {busy ? "Working…" : "Choose a file"}
            <input
              class="sr-only"
              type="file"
              aria-label="Resume file"
              accept=".pdf,.docx,.txt,.md,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,text/plain,text/markdown"
              disabled={busy}
              onChange={(e) => {
                const file = (e.target as HTMLInputElement).files?.[0];
                if (file) {
                  run(() => uploadResume(file), `Read ${file.name}. Fit scores are being updated.`);
                }
              }}
            />
          </label>
          <button class="btn ghost" type="button" aria-expanded={pasting} onClick={() => setPasting((p) => !p)}>
            Paste text instead
          </button>
        </div>
        {pasting && (
          <form
            class="stack"
            onSubmit={(e) => {
              e.preventDefault();
              run(() => pasteResume("Pasted resume", pasteText), "Saved the pasted resume.").then(() => setPasting(false));
            }}
          >
            <textarea
              class="input textarea"
              rows={10}
              aria-label="Resume text"
              value={pasteText}
              onInput={(e) => setPasteText((e.target as HTMLTextAreaElement).value)}
            />
            <div class="drawer-row end">
              <button class="btn primary" data-role="purple" type="submit" disabled={busy || !pasteText.trim()}>
                Save text
              </button>
            </div>
          </form>
        )}
      </div>

      {resume && (
        <div class="panel">
          <h2>Keywords</h2>
          <p class="muted">
            The fit score compares these with each vacancy. They were proposed from your resume: remove what is not a
            skill, add what is missing.
            {resume.years != null && (
              <>
                {" "}
                Your resume shows about <span class="num">{resume.years}</span> years of experience.
              </>
            )}
          </p>
          <ul class="chips" aria-label="Keywords">
            {keywords.map((k) => (
              <li key={k} class="chip">
                {k}
                <button
                  type="button"
                  class="chip-remove"
                  aria-label={`Remove ${k}`}
                  onClick={() => setKeywords(keywords.filter((x) => x !== k))}
                >
                  ×
                </button>
              </li>
            ))}
          </ul>
          <form
            class="drawer-row"
            onSubmit={(e) => {
              e.preventDefault();
              setKeywords(parseKeywords(newKeyword, keywords));
              setNewKeyword("");
            }}
          >
            <label class="grow">
              <span class="sr-only">Add keywords</span>
              <input
                class="input"
                placeholder="Add keywords, separated by commas"
                value={newKeyword}
                onInput={(e) => setNewKeyword((e.target as HTMLInputElement).value)}
              />
            </label>
            <button class="btn" type="submit" disabled={!newKeyword.trim()}>
              Add
            </button>
            <button
              class="btn primary"
              data-role="purple"
              type="button"
              disabled={busy || !keywordsChanged}
              onClick={() => run(() => saveKeywords(keywords), "Keywords saved. Fit scores are being updated.")}
            >
              Save keywords
            </button>
          </form>
        </div>
      )}

      {resume && (
        <div class="panel">
          <h2>Text</h2>
          <details>
            <summary>Show the text read from {resume.fileName ?? "the pasted resume"}</summary>
            <pre class="resume-text">{resume.text}</pre>
          </details>
          <div class="drawer-row">
            {resume.hasOriginal && (
              <a class="btn ghost" href="/api/resume/original" download>
                Download the original
              </a>
            )}
            <span class="grow" />
            <button
              class="btn danger"
              type="button"
              disabled={busy}
              onClick={() => {
                if (window.confirm("Delete the resume and every fit score?")) {
                  run(deleteResume, "The resume was deleted.").then(() => {
                    setResume(null);
                    setKeywords([]);
                  });
                }
              }}
            >
              Delete resume
            </button>
          </div>
        </div>
      )}
    </section>
  );
}
