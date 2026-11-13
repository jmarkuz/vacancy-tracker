import { useEffect, useState } from "preact/hooks";
import { getReplies, type Replies } from "../api/dashboard";
import { medianText, outcomeRows, periodText, repliedPercent } from "../domain/dashboard";
import { LoadFailed } from "./LoadFailed";

const PERIODS = [30, 90];

/** Dashboard card: of the applications sent in the period, how many got a mail, how fast, and what the mails were. */
export function RepliesCard({ dataVersion = 0 }: { dataVersion?: number }) {
  const [days, setDays] = useState(30);
  const [data, setData] = useState<Replies | null>(null);
  const [failed, setFailed] = useState(false);
  const [reload, setReload] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setFailed(false);
    getReplies(days, controller.signal)
      .then(setData)
      .catch(() => {
        if (!controller.signal.aborted) {
          setFailed(true);
        }
      });
    return () => controller.abort();
  }, [days, dataVersion, reload]);

  // The figures of another period are not shown under a toggle that already moved.
  const current = data && data.days === days ? data : null;

  return (
    <div class="panel replies-card" data-testid="replies-card">
      <div class="replies-head">
        <h2>Replies</h2>
        <div class="replies-periods" role="group" aria-label="Period">
          {PERIODS.map((p) => (
            <button key={p} type="button" class="chip" aria-pressed={p === days} onClick={() => setDays(p)}>
              {p} days
            </button>
          ))}
        </div>
      </div>
      <p class="muted small">Mails linked to the applications you sent in {periodText(days)}, received after you applied.</p>
      {failed ? (
        <LoadFailed what="the replies" onRetry={() => setReload((n) => n + 1)} />
      ) : !current ? (
        <p class="muted" role="status">Loading the replies…</p>
      ) : current.applied === 0 ? (
        <p class="muted">No applications in this period yet.</p>
      ) : (
        <Body data={current} />
      )}
    </div>
  );
}

function Body({ data }: { data: Replies }) {
  const percent = repliedPercent(data.applied, data.replied);
  return (
    <div>
      <dl class="replies-stats">
        <div>
          <dt>Applied</dt>
          <dd class="num" data-stat="applied">{data.applied.toLocaleString("en-US")}</dd>
        </div>
        <div>
          <dt>Replied</dt>
          <dd class="num" data-stat="replied">{data.replied.toLocaleString("en-US")} <span class="small subtle">({percent}%)</span></dd>
        </div>
        <div>
          <dt>Median wait</dt>
          {data.medianDays === null ? (
            <dd class="num" data-stat="median" aria-label={medianText(null)}>–</dd>
          ) : (
            <dd class="num" data-stat="median">{medianText(data.medianDays)}</dd>
          )}
        </div>
      </dl>
      {data.replied === 0 ? (
        <p class="muted">No replies yet. None of these applications has a linked mail.</p>
      ) : (
        <div class="table-wrap">
          <table class="replies-outcomes" aria-label="Mails by outcome">
            <thead><tr><th scope="col">Outcome</th><th scope="col" class="num">Mails</th></tr></thead>
            <tbody>
              {outcomeRows(data.outcomes).map((r) => (
                <tr key={r.id} data-outcome={r.id}>
                  <td><span class={`mail-category ${r.className}`}>{r.label}</span></td>
                  <td class="num">{r.count.toLocaleString("en-US")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
