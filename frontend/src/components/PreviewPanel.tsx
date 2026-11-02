import { useEffect, useState } from "preact/hooks";
import { previewProfile, type Preview, type ProfileInput } from "../api/profiles";
import { previewSummary, sampleLabel } from "../domain/preview";

interface Props {
  draft: ProfileInput;
  /** Only a valid draft can be previewed. */
  valid: boolean;
  /** The stored profile being edited, so its own company lists are applied; null for a new one. */
  profileId?: string | null;
}

/** Live preview (T-056): how many existing vacancies the draft would match, with samples and their reasons. */
export function PreviewPanel({ draft, valid, profileId }: Props) {
  const [preview, setPreview] = useState<Preview | null>(null);
  const [failed, setFailed] = useState(false);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!valid) {
      return;
    }
    setLoading(true);
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      previewProfile(draft, controller.signal, profileId ?? undefined)
        .then((p) => {
          setPreview(p);
          setFailed(false);
          setLoading(false);
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setFailed(true);
            setLoading(false);
          }
        });
    }, 600);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [draft, valid, profileId]);

  return (
    <section class="editor-section preview-panel" aria-labelledby="preview-title" aria-busy={loading}>
      <h3 id="preview-title">Preview</h3>
      {!valid && !preview && <p class="muted">Fix the marked fields to see how many vacancies this profile would match.</p>}
      {failed && <p class="muted" role="alert">The preview could not be calculated right now.</p>}
      {preview && (
        <div class={loading || !valid ? "preview-stale" : undefined}>
          <p role="status" aria-live="polite">{previewSummary(preview)}</p>
          {preview.samples.length > 0 && (
            <ul class="samples" aria-label="Sample vacancies">
              {preview.samples.map((s) => (
                <li key={s.vacancyId} class="sample">
                  <div><strong>{s.title}</strong> <span class="muted">{s.company}</span></div>
                  <div class="muted small">{sampleLabel(s)}</div>
                  {s.reasons.length > 0 && (
                    <ul class="reasons">
                      {s.reasons.map((r) => <li key={r.criterionId + r.kind}>{r.text}</li>)}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}
