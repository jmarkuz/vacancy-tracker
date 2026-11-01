import { useCallback, useEffect, useState } from "preact/hooks";
import { getJson, ApiError } from "../api/client";
import type { PageResponse, Vacancy } from "../api/types";
import { setQuery } from "../router";
import { FilterBar } from "../components/FilterBar";
import { ViewBar, project } from "../components/ViewBar";
import { parseCriteria, serializeCriteria, type Criterion } from "../domain/criteria";
import type { SavedView } from "../api/views";
import { COLUMNS, DEFAULT_COLUMNS, type Column } from "./columns";
import { VacancyDrawer } from "./VacancyDrawer";

const PAGE_SIZE = 100;
const DEFAULT_SORT = "lastSeenAt:desc";

interface Props {
  params: URLSearchParams;
}

/** Sort, page and visible columns are all in the URL, so a view can be bookmarked and shared. */
export function Pipeline({ params }: Props) {
  const sort = params.get("sort") ?? DEFAULT_SORT;
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const visible = resolveColumns(params.get("cols"));
  const openId = params.get("open");
  const filterRaw = params.get("filter");
  const criteria = parseCriteria(filterRaw);
  const cols = params.get("cols");
  const viewId = params.get("view");
  const closeDrawer = useCallback(() => setQuery({ open: null }), []);

  const [data, setData] = useState<PageResponse<Vacancy> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const query = new URLSearchParams({ sort, page: String(page), size: String(PAGE_SIZE) });
    if (filterRaw) {
      query.set("filter", filterRaw);
    }
    getJson<PageResponse<Vacancy>>(`/api/vacancies?${query}`, controller.signal)
      .then((result) => {
        setData(result);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) {
          return;
        }
        setError(e instanceof ApiError ? e.message : "Could not load vacancies. Check the connection and retry.");
        setLoading(false);
      });
    return () => controller.abort();
  }, [sort, page, filterRaw, attempt]);

  const pageCount = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <section aria-labelledby="pipeline-title">
      <div class="toolbar">
        <h1 id="pipeline-title">Pipeline</h1>
        <span class="grow" />
        <ColumnPicker selected={visible} />
      </div>

      <FilterBar criteria={criteria} onChange={(next) => setFilter(next)} />
      <ViewBar
        viewId={viewId}
        criteria={criteria}
        sort={params.get("sort")}
        cols={cols}
        onApply={applyView}
      />

      {error && (
        <div class="notice error" role="alert">
          <span>{error}</span>
          <button class="btn" type="button" onClick={() => setAttempt((n) => n + 1)}>
            Retry
          </button>
        </div>
      )}

      {!error && data && data.total === 0 && !loading && (
        <div class="panel empty-state">
          <h2>No vacancies yet</h2>
          <p class="muted">Add one by URL or refresh the sources once they are configured.</p>
        </div>
      )}

      {data && data.total > 0 && (
        <>
          <div class="table-wrap" aria-busy={loading}>
            <table>
              <thead>
                <tr>
                  {visible.map((col) => (
                    <SortableHeader key={col.id} column={col} sort={sort} onSort={(next) => setQuery({ sort: next, page: null })} />
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((v) => (
                  <tr key={v.id}>
                    {visible.map((col) => (
                      <td key={col.id} class={col.numeric ? "numeric" : undefined}>
                        {col.id === "title" ? (
                          <button type="button" class="row-open" onClick={() => setQuery({ open: v.id })}>
                            {col.render(v)}
                          </button>
                        ) : (
                          col.render(v)
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <ul class="vcards" aria-label="Vacancies">
            {data.items.map((v) => (
              <li key={v.id} class="vcard">
                <button type="button" class="row-open vcard-title" onClick={() => setQuery({ open: v.id })}>
                  {v.title}
                </button>
                <div class="muted">{v.company}</div>
                <div class="vcard-meta">
                  <span class="badge-slot">{COLUMNS.find((c) => c.id === "status")?.render(v)}</span>
                  <span class="num">{COLUMNS.find((c) => c.id === "salary")?.render(v)}</span>
                </div>
              </li>
            ))}
          </ul>

          <nav class="pager" aria-label="Pages">
            <button class="btn" type="button" disabled={page === 0} onClick={() => setQuery({ page: String(page - 1) })}>
              Previous
            </button>
            <span class="num" aria-live="polite">
              Page {page + 1} of {pageCount} · {data.total.toLocaleString()} vacancies
            </span>
            <button
              class="btn"
              type="button"
              disabled={page + 1 >= pageCount}
              onClick={() => setQuery({ page: String(page + 1) })}
            >
              Next
            </button>
          </nav>
        </>
      )}

      {loading && !data && <p class="muted" role="status">Loading vacancies…</p>}

      {openId && (
        <VacancyDrawer
          id={openId}
          onClose={closeDrawer}
          onChange={() => setAttempt((n) => n + 1)}
        />
      )}
    </section>
  );
}

/** Writes the filter into the URL and returns to the first page. */
function setFilter(next: Criterion[]): void {
  setQuery({ filter: serializeCriteria(next), page: null });
}

/** Applying a view replaces sort, columns and filter; null only detaches the view. */
function applyView(view: SavedView | null): void {
  if (!view) {
    setQuery({ view: null });
    return;
  }
  setQuery({
    view: view.id,
    filter: serializeCriteria(project(view.criteria)),
    sort: view.sort,
    cols: view.visibleColumns.length ? view.visibleColumns.join(",") : null,
    page: null,
  });
}

/** Unknown column ids in the URL are ignored; at least one column always shows. */
function resolveColumns(raw: string | null): Column[] {
  const ids = raw ? raw.split(",") : DEFAULT_COLUMNS;
  const picked = COLUMNS.filter((c) => ids.includes(c.id));
  return picked.length > 0 ? picked : COLUMNS.filter((c) => DEFAULT_COLUMNS.includes(c.id));
}

function SortableHeader({ column, sort, onSort }: { column: Column; sort: string; onSort: (next: string) => void }) {
  if (!column.sortKey) {
    return <th scope="col">{column.label}</th>;
  }
  const [currentKey, direction] = sort.split(":");
  const active = currentKey === column.sortKey;
  const nextDirection = active && direction === "asc" ? "desc" : "asc";
  const ariaSort = active ? (direction === "asc" ? "ascending" : "descending") : "none";
  return (
    <th scope="col" aria-sort={ariaSort} class={column.numeric ? "numeric" : undefined}>
      <button type="button" class="sort-button" onClick={() => onSort(`${column.sortKey}:${nextDirection}`)}>
        {column.label}
        <span aria-hidden="true">{active ? (direction === "asc" ? " ▲" : " ▼") : ""}</span>
      </button>
    </th>
  );
}

function ColumnPicker({ selected }: { selected: Column[] }) {
  const selectedIds = selected.map((c) => c.id);
  const toggle = (id: string, on: boolean) => {
    const next = on ? [...selectedIds, id] : selectedIds.filter((x) => x !== id);
    if (next.length === 0) {
      return;
    }
    // Keep the table's column order, not the click order.
    const ordered = COLUMNS.map((c) => c.id).filter((x) => next.includes(x));
    setQuery({ cols: ordered.join(",") });
  };
  return (
    <details class="column-picker">
      <summary class="btn">Columns</summary>
      <fieldset>
        <legend class="sr-only">Visible columns</legend>
        {COLUMNS.map((c) => (
          <label key={c.id} class="check">
            <input
              type="checkbox"
              checked={selectedIds.includes(c.id)}
              disabled={selectedIds.includes(c.id) && selectedIds.length === 1}
              onChange={(e) => toggle(c.id, (e.target as HTMLInputElement).checked)}
            />
            {c.label}
          </label>
        ))}
      </fieldset>
    </details>
  );
}
