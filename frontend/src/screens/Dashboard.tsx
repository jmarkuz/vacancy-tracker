import { useEffect, useState } from "preact/hooks";
import { getDashboard, type Dashboard as Data } from "../api/dashboard";
import { listSources, type SourceStatus } from "../api/sources";
import { barPercent, chartSummary, funnelMax, kpis, weekLabel, weekMax } from "../domain/dashboard";
import { healthDetail, healthLabel } from "../domain/sources";
import { LoadFailed } from "../components/LoadFailed";
import { formatDate } from "./columns";

/** Dashboard (T-060): KPI cards that open a filtered list, top matches, funnel, applications per week, next actions. */
/** Beacon roles of the metrics: new work is blue, work in progress green, interviews purple, overdue amber. */
const KPI_ROLE: Record<string, "blue" | "green" | "purple" | "amber"> = {
  "new-today": "blue",
  inbox: "blue",
  active: "green",
  interviews: "purple",
  overdue: "amber",
};

export function Dashboard({ dataVersion = 0 }: { dataVersion?: number }) {
  const [data, setData] = useState<Data | null>(null);
  const [sources, setSources] = useState<SourceStatus[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [sourcesFailed, setSourcesFailed] = useState(false);
  const [sourcesReload, setSourcesReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    getDashboard(controller.signal).then(setData).catch(() => {
      if (!controller.signal.aborted) {
        setError("Could not load the dashboard.");
      }
    });
    return () => controller.abort();
  }, [dataVersion]);

  useEffect(() => {
    const controller = new AbortController();
    setSourcesFailed(false);
    listSources(controller.signal)
      .then(setSources)
      .catch(() => {
        if (!controller.signal.aborted) {
          setSourcesFailed(true);
        }
      });
    return () => controller.abort();
  }, [dataVersion, sourcesReload]);

  if (error) {
    return <div class="notice error" role="alert">{error}</div>;
  }
  if (!data) {
    return <p class="muted" role="status">Loading the dashboard…</p>;
  }

  const funnelTop = funnelMax(data.funnel);
  const weeksTop = weekMax(data.applicationsPerWeek);
  const chartH = 120;
  const slot = 56;

  return (
    <section aria-labelledby="dash-title">
      <h1 id="dash-title">Dashboard</h1>

      <div class="grid-kpi">
        {kpis(data, new Date()).map((k) => (
          <a key={k.id} class="kpi" href={k.href} data-kpi={k.id} data-role={KPI_ROLE[k.id] ?? "blue"}>
            <div class="value num">{k.value.toLocaleString("en-US")}</div>
            <div class="label">{k.label}</div>
          </a>
        ))}
      </div>

      <div class="panels">
        <div class="stack">
          <div class="panel">
            <h2>Top matches</h2>
            {data.topMatches.length === 0 ? (
              <p class="muted">No scored vacancies in the Inbox yet.</p>
            ) : (
              <ul class="plain-list" aria-label="Top matches">
                {data.topMatches.map((m) => (
                  <li key={m.id} class="list-item">
                    <a class="meta" href={`#/inbox?open=${m.id}`}>
                      <div>{m.title}</div>
                      <div class="small subtle">{m.company}</div>
                    </a>
                    <span class="badge score num" aria-label={`Score ${m.score}`}>{m.score}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div class="panel">
            <h2>Funnel: Saved to Offer</h2>
            <p class="muted small">Vacancies that reached each stage or a later one.</p>
            {data.funnel.map((s) => (
              <div key={s.status} class="funnel-row" data-stage={s.status}>
                <span>{s.status}</span>
                <svg class="funnel-svg" viewBox="0 0 100 10" preserveAspectRatio="none" role="presentation" aria-hidden="true">
                  <rect x="0" y="0" width="100" height="10" class="funnel-track" />
                  <rect x="0" y="0" width={barPercent(s.count, funnelTop)} height="10" class="funnel-bar" />
                </svg>
                <span class="num">{s.count}</span>
              </div>
            ))}
          </div>
        </div>

        <div class="stack">
          <div class="panel">
            <h2>Applications per week</h2>
            <svg class="week-chart" viewBox={`0 0 ${slot * data.applicationsPerWeek.length} ${chartH + 36}`} role="img" aria-label={chartSummary(data.applicationsPerWeek)}>
              {data.applicationsPerWeek.map((w, i) => {
                const h = (barPercent(w.applications, weeksTop) / 100) * chartH;
                return (
                  <g key={w.start} transform={`translate(${i * slot}, 0)`}>
                    <rect class="week-bar" x="12" y={chartH - h + 14} width={slot - 24} height={h} rx="2" />
                    <text class="week-value" x={slot / 2} y={chartH - h + 10} text-anchor="middle">{w.applications}</text>
                    <text class="week-label" x={slot / 2} y={chartH + 30} text-anchor="middle">{weekLabel(w.start)}</text>
                  </g>
                );
              })}
            </svg>
          </div>

          <div class="panel">
            <h2>Next actions</h2>
            {data.nextActions.length === 0 ? (
              <p class="muted">No next actions are set.</p>
            ) : (
              <ul class="plain-list" aria-label="Next actions">
                {data.nextActions.map((a) => (
                  <li key={a.id} class="list-item">
                    <a class="meta" href={`#/pipeline?open=${a.id}`}>
                      <div>{a.note || a.title}</div>
                      <div class={`small num ${a.overdue ? "overdue" : "subtle"}`}>
                        {a.overdue ? "Overdue · " : ""}{formatDate(a.dueAt)} · {a.company}
                      </div>
                    </a>
                    <span class="badge">{a.status}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>
      </div>

      <div class="panel">
        <h2>Source health</h2>
        {sourcesFailed ? (
          <LoadFailed what="the source health" onRetry={() => setSourcesReload((n) => n + 1)} />
        ) : sources.length === 0 ? (
          <p class="muted">No source information yet.</p>
        ) : (
          <div class="table-wrap">
            <table aria-label="Source health">
              <thead><tr><th scope="col">Source</th><th scope="col">Health</th><th scope="col">Detail</th></tr></thead>
              <tbody>
                {sources.map((s) => (
                  <tr key={s.id}>
                    <td>{s.id}</td>
                    <td><span class={`badge health-${s.health}`}>{healthLabel(s.health)}</span></td>
                    <td class="muted small">{healthDetail(s)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </section>
  );
}
