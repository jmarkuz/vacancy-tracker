import { useState } from "preact/hooks";
import { ApiError } from "../api/client";
import { EXPORTS, importFile, type ImportReport } from "../api/transfer";

/** Export and import (T-079): downloads of CSV, XLSX and the JSON backup, and an import with its row report. */
export function Transfer() {
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [report, setReport] = useState<ImportReport | null>(null);
  const [error, setError] = useState<string | null>(null);

  const upload = async () => {
    if (!file) {
      return;
    }
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      setReport(await importFile(file));
    } catch (e: unknown) {
      setError(e instanceof ApiError ? e.message : "The import failed. Nothing was stored.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="transfer-title" class="stack">
      <div class="toolbar">
        <h1 id="transfer-title">Export &amp; import</h1>
      </div>

      <div class="panel">
        <h2>Export</h2>
        <p class="muted">Take your data out at any time. Exports include every vacancy, whatever its status.</p>
        <ul class="link-list">
          {EXPORTS.map((x) => (
            <li key={x.href}>
              <a class="btn" href={x.href} download>
                {x.label}
              </a>{" "}
              <span class="muted small">{x.hint}</span>
            </li>
          ))}
        </ul>
      </div>

      <div class="panel">
        <h2>Import</h2>
        <p class="muted">
          A CSV export or a JSON backup. A CSV row with an id merges into that vacancy and keeps the fields you edited by
          hand; each rejected row is listed with its reason.
        </p>
        <form
          class="drawer-row"
          onSubmit={(e) => {
            e.preventDefault();
            upload();
          }}
        >
          <label>
            <span class="sr-only">File to import</span>
            <input
              class="input"
              type="file"
              accept=".csv,.json,text/csv,application/json"
              onChange={(e) => setFile((e.target as HTMLInputElement).files?.[0] ?? null)}
            />
          </label>
          <button class="btn primary" type="submit" disabled={!file || busy}>
            {busy ? "Importing…" : "Import"}
          </button>
        </form>
        {error && (
          <div class="notice error" role="alert">
            {error}
          </div>
        )}
        {report && (
          <div role="status">
            <p>
              Imported <span class="num">{report.imported}</span> of <span class="num">{report.total}</span> rows.
            </p>
            {report.errors.length > 0 && (
              <table class="table compact">
                <thead>
                  <tr>
                    <th scope="col">Row</th>
                    <th scope="col">Field</th>
                    <th scope="col">Problem</th>
                  </tr>
                </thead>
                <tbody>
                  {report.errors.map((r) => (
                    <tr key={`${r.row}-${r.field}`}>
                      <td class="num">{r.row}</td>
                      <td>{r.field}</td>
                      <td>{r.message}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
