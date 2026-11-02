import { useEffect, useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { type Analysis, getAnalysis, type MatchItem, PRIORITY_LABEL, runAnalysis, STATUS_LABEL } from "../api/analysis";
import { FitBadge } from "./FitBadge";
import { MatchBar } from "./MatchBar";

interface Props {
  vacancyId: string;
  presetId: string | null;
  /** Bumped when a document version is saved, so its match is computed. */
  docsVersion: number;
}

/**
 * How the vacancy fits (T-081, T-095). Without AI: the quick fit from the resume keywords, labelled as a quick
 * estimate. With AI on, the drawer runs the analysis once (cached on the server): the Employer match of the resume
 * against the employer's checklist, and the Goal match of the vacancy against the preset's goal.
 */
export function AnalysisPanel({ vacancyId, presetId, docsVersion }: Props) {
  const [analysis, setAnalysis] = useState<Analysis | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let live = true;
    setError(null);
    getAnalysis(vacancyId, presetId, controller.signal)
      .then(async (known) => {
        setAnalysis(known);
        if (!known.aiEnabled) {
          return;
        }
        setRunning(true);
        try {
          const done = await runAnalysis(vacancyId, presetId);
          if (live) {
            setAnalysis(done);
          }
        } finally {
          if (live) {
            setRunning(false);
          }
        }
      })
      .catch((e: unknown) => {
        if (live && !controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : "The analysis could not be loaded.");
        }
      });
    return () => {
      live = false;
      controller.abort();
    };
  }, [vacancyId, presetId, docsVersion]);

  const resume = analysis?.resume ?? analysis?.baseResume ?? null;

  return (
    <section class="drawer-section" aria-labelledby="analysis-title" aria-busy={running}>
      <h3 id="analysis-title">How this vacancy fits</h3>
      {error && (
        <div class="notice error" role="alert">
          {error}
        </div>
      )}
      {analysis === null && !error && <p class="muted">Loading…</p>}
      {running && (
        <p class="muted small" role="status">
          Analysing the vacancy with AI…
        </p>
      )}

      {analysis && (
        <p class="small">
          Quick fit <FitBadge fit={analysis.quickFit} />{" "}
          <span class="muted">
            {analysis.quickFit
              ? "a quick estimate from your resume keywords, without AI."
              : "upload your resume to see a quick estimate."}
          </span>
        </p>
      )}
      {analysis && !analysis.aiEnabled && analysis.quickFit && (
        <ul class="reasons">
          {analysis.quickFit.reasons.map((r, i) => (
            <li key={i} class={`reason reason-${r.kind}`}>
              {r.text}
            </li>
          ))}
        </ul>
      )}
      {analysis && !analysis.aiEnabled && (
        <p class="muted small">
          <a href="#/settings">Switch AI on</a> for the Employer match against the employer's requirements and the Goal match against
          your preset.
        </p>
      )}
      {analysis?.checklistState === "stale" && !running && (
        <p class="small warn-text">The vacancy text changed: the earlier analysis no longer applies and is computed again.</p>
      )}

      {analysis?.aiEnabled && analysis.checklist && (
        <div class="stack">
          {resume ? (
            <MatchBar
              label={`Employer match, ${resume.source === "version" ? `resume v${resume.version}` : "base resume"}`}
              score={resume.match.score}
              note="How well the resume covers the vacancy's requirements, not a chance of being hired."
            />
          ) : (
            <h4>Employer match</h4>
          )}
          {analysis.checklist.coreProblem && <p class="small muted">Core problem of this hire: {analysis.checklist.coreProblem}</p>}
          {resume ? <Requirements items={resume.match.items} /> : <p class="muted small">Fill in the base resume of the preset to see it.</p>}
        </div>
      )}

      {analysis?.aiEnabled && (
        <div class="stack">
          {analysis.goal ? (
            <MatchBar
              label={`Goal match${analysis.goalState === "stale" ? " (from the earlier text)" : ""}`}
              score={analysis.goal.score}
              note="How well the vacancy fits your preset's goal. Tailoring documents does not change it."
            />
          ) : (
            <h4>Goal match</h4>
          )}
          {analysis.goalState === "not_ready" && (
            <p class="muted small">
              Fill in the main goal of the <a href={`#/presets${presetId ? `?id=${encodeURIComponent(presetId)}` : ""}`}>preset</a> to see it.
            </p>
          )}
          {analysis.goal?.dealBreaker && (
            <div class="notice error" role="alert">
              Deal-breaker: {analysis.goal.dealBreakerReason}
            </div>
          )}
          {analysis.goal && (
            <ul class="reasons">
              {analysis.goal.parameters.map((p) => (
                <li key={p.name}>
                  <strong>{p.name}</strong> <span class="num">{p.score}</span>/100 · weight {Math.round(p.share * 100)}% — {p.explanation}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </section>
  );
}

/** The checklist with each requirement's status, in words (not only colour), and the proof. */
export function Requirements({ items }: { items: MatchItem[] }) {
  return (
    <ul class="requirements">
      {items.map((item) => (
        <li key={item.requirementId} class={`req req-${item.status}`}>
          <span class="req-status">{STATUS_LABEL[item.status]}</span> <span class="muted small">{PRIORITY_LABEL[item.priority]}</span> {item.text}
          {item.quote && <q class="small"> {item.quote}</q>}
          {item.note && <span class="small muted"> {item.note}</span>}
        </li>
      ))}
    </ul>
  );
}
