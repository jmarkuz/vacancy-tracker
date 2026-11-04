import { useCallback, useEffect, useRef, useState } from "preact/hooks";
import { getJson, ApiError } from "../api/client";
import { changeStatus } from "../api/vacancies";
import { Board } from "../components/Board";
import { Toast } from "../components/Toast";
import { bulkStatus, bulkUndo } from "../api/bulk";
import { bulkSummary, optionLabel, targetOptions, toggle, togglePage, undoItems, undoSummary } from "../domain/bulk";
import { BOARD_COLUMNS, withStatus } from "../domain/kanban";
import type { PageResponse, Vacancy } from "../api/types";
import { setQuery } from "../router";
import { FilterBar } from "../components/FilterBar";
import { ViewBar, project } from "../components/ViewBar";
import { parseCriteria, serializeCriteria, type Criterion } from "../domain/criteria";
import type { SavedView } from "../api/views";
import { COLUMNS, DEFAULT_COLUMNS, type Column } from "./columns";
import { VacancyDrawer } from "./VacancyDrawer";
import { Icon } from "../components/Icon";
import { AddVacancy } from "../components/AddVacancy";
import { getFits, type ResumeFit } from "../api/resume";
import { fitsById } from "../domain/fit";
import { FitBadge } from "../components/FitBadge";
import { ColumnResizer } from "../components/ColumnResizer";
import { loadWidths, saveWidths, widthOf, withoutWidth, withWidth, type Widths } from "../domain/columnWidths";

const PAGE_SIZE = 100;
/** Room kept for the title column, which has no width of its own, and for the checkbox column. */
const TITLE_ROOM = 280;
const CHECK_WIDTH = 36;
const DEFAULT_SORT = "lastSeenAt:desc";

interface Props {
  params: URLSearchParams;
  /** Grows when a refresh stored new data: the list loads again, keeping the open drawer and selection. */
  dataVersion?: number;
}

/** Sort, page and visible columns are all in the URL, so a view can be bookmarked and shared. */
export function Pipeline({ params, dataVersion = 0 }: Props) {
  const sort = params.get("sort") ?? DEFAULT_SORT;
  const page = Math.max(0, Number(params.get("page") ?? 0) || 0);
  const visible = resolveColumns(params.get("cols"));
  const openId = params.get("open");
  const filterRaw = params.get("filter");
  const criteria = parseCriteria(filterRaw);
  const cols = params.get("cols");
  const viewId = params.get("view");
  const board = params.get("layout") === "board";
  const closeDrawer = useCallback(() => setQuery({ open: null }), []);

  const [data, setData] = useState<PageResponse<Vacancy> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);
  const applying = useRef(false);
  const [target, setTarget] = useState("");
  const [toast, setToast] = useState<{ message: string; tone: "info" | "error"; undo?: () => void } | null>(null);
  const [adding, setAdding] = useState(false);
  const [fits, setFits] = useState<Record<string, ResumeFit>>({});
  const [widths, setWidths] = useState<Widths>(() => loadWidths());
  const resizeColumn = (id: string, px: number) =>
    setWidths((current) => {
      const next = withWidth(current, id, px);
      saveWidths(next);
      return next;
    });
  const resetColumn = (id: string) =>
    setWidths((current) => {
      const next = withoutWidth(current, id);
      saveWidths(next);
      return next;
    });

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const query = new URLSearchParams({ sort, page: String(page), size: String(PAGE_SIZE) });
    if (board) {
      // The board shows pipeline statuses only; without this the first page would be full of Inbox vacancies.
      query.set("filter", JSON.stringify([...criteria, { field: "status", operator: "in", value: BOARD_COLUMNS }]));
    } else if (filterRaw) {
      query.set("filter", filterRaw);
    }
    getJson<PageResponse<Vacancy>>(`/api/vacancies?${query}`, controller.signal)
      .then((result) => {
        // A page past the end (rows moved away, an old link) goes to the last page instead of showing nothing.
        const lastPage = Math.max(0, Math.ceil(result.total / PAGE_SIZE) - 1);
        if (result.total > 0 && page > lastPage) {
          setQuery({ page: lastPage === 0 ? null : String(lastPage) });
          return;
        }
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
  }, [sort, page, filterRaw, attempt, board, dataVersion]);

  // A new page, filter or layout shows different rows, so an old selection would act on rows out of sight.
  useEffect(() => {
    setSelected([]);
    setTarget("");
  }, [page, filterRaw, board, sort]);

  // A row that left the list (edited in the drawer, filtered out) must not stay selected for a bulk change.
  useEffect(() => {
    if (data) {
      const visibleIds = new Set(data.items.map((v) => v.id));
      setSelected((current) => (current.every((id) => visibleIds.has(id)) ? current : current.filter((id) => visibleIds.has(id))));
    }
  }, [data]);

  // Fit to the resume for the rows on show (T-081); none when no resume is uploaded.
  useEffect(() => {
    const controller = new AbortController();
    getFits((data?.items ?? []).map((v) => v.id), controller.signal)
      .then((list) => setFits(fitsById(list)))
      .catch(() => undefined);
    return () => controller.abort();
  }, [data]);

  const selectedRows = (data?.items ?? []).filter((v) => selected.includes(v.id));
  const options = targetOptions(selectedRows);

  const applyBulk = async () => {
    if (!target || selected.length === 0 || applying.current) {
      return;
    }
    applying.current = true;
    try {
      const result = await bulkStatus(selected, target);
      setSelected([]);
      setTarget("");
      setAttempt((n) => n + 1);
      const items = undoItems(result, target);
      setToast({
        message: bulkSummary(result, target),
        tone: result.changed.length === 0 ? "error" : "info",
        undo: items.length === 0 ? undefined : async () => {
          try {
            const undone = await bulkUndo(items);
            setAttempt((n) => n + 1);
            setToast({ message: undoSummary(undone), tone: undone.skipped.length > 0 ? "error" : "info" });
          } catch (e: unknown) {
            setToast({ message: e instanceof Error ? e.message : "Could not undo.", tone: "error" });
          }
        },
      });
    } catch (e: unknown) {
      setToast({ message: e instanceof Error ? e.message : "The change failed. Nothing was changed.", tone: "error" });
    } finally {
      applying.current = false;
    }
  };

  const pageCount = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <section aria-labelledby="pipeline-title">
      <div class="toolbar">
        <h1 id="pipeline-title">Pipeline</h1>
        <span class="grow" />
        <div class="filters" role="group" aria-label="Layout">
          <button type="button" class="chip" aria-pressed={!board} onClick={() => setQuery({ layout: null })}>
            <Icon name="table" />
            Table
          </button>
          <button type="button" class="chip" aria-pressed={board} onClick={() => setQuery({ layout: "board" })}>
            <Icon name="board" />
            Board
          </button>
        </div>
        {!board && <ColumnPicker selected={visible} />}
        <button type="button" class="btn primary" data-role="green" aria-expanded={adding} onClick={() => setAdding((a) => !a)}>
          <Icon name="plus" />
          Add vacancy
        </button>
      </div>

      {adding && (
        <AddVacancy
          onClose={() => setAdding(false)}
          onAdded={(id) => {
            setAdding(false);
            setAttempt((n) => n + 1);
            setQuery({ open: id });
          }}
        />
      )}

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
            <Icon name="refresh" />
            Retry
          </button>
        </div>
      )}

      {!error && data && data.total === 0 && !loading && (
        <div class="panel empty-state">
          <h2>No vacancies yet</h2>
          <p class="muted">Add one with "Add vacancy", or refresh the sources once they are configured.</p>
        </div>
      )}

      {board && data && data.total > 0 && (
        <>
          <Board items={data.items} onOpen={(id) => setQuery({ open: id })}
            onMove={async (v, status) => {
              const updated = await changeStatus(v.id, status);
              setData((d) => (d ? { ...d, items: withStatus(d.items, v.id, updated.status) } : d));
            }} />
          {data.total > data.items.length && (
            <p class="muted small" role="status">Showing {data.items.length} of {data.total.toLocaleString()} vacancies. Narrow the list with a filter.</p>
          )}
        </>
      )}

      {!board && data && data.total > 0 && (
        <>
          {selected.length > 0 && (
            <div class="bulk-bar" role="region" aria-label="Bulk actions">
              <span class="num" role="status">{selected.length} selected</span>
              <select class="input" aria-label="Change status to" value={target} onChange={(e) => setTarget((e.target as HTMLSelectElement).value)}>
                <option value="">Change status to…</option>
                {options.map((o) => <option key={o.status} value={o.status}>{optionLabel(o)}</option>)}
              </select>
              <button type="button" class="btn primary" data-role="green" disabled={!target} onClick={applyBulk}>
                <Icon name="check" />
                Apply
              </button>
              <button type="button" class="btn" onClick={() => setSelected([])}>
                <Icon name="close" />
                Clear selection
              </button>
            </div>
          )}
          <div class="table-wrap" aria-busy={loading}>
            <table class="pipeline-table" style={{ minWidth: `${tableMinWidth(visible, widths)}px` }}>
              <colgroup>
                <col style={{ width: `${CHECK_WIDTH}px` }} />
                {visible.map((col) => {
                  const px = widthOf(col.id, widths);
                  return <col key={col.id} style={px ? { width: `${px}px` } : undefined} />;
                })}
              </colgroup>
              <thead>
                <tr>
                  <th scope="col" class="check-col">
                    <input type="checkbox" aria-label="Select all rows on this page"
                      checked={data.items.length > 0 && data.items.every((v) => selected.includes(v.id))}
                      onChange={() => setSelected(togglePage(selected, data.items.map((v) => v.id)))} />
                  </th>
                  {visible.map((col) => (
                    <SortableHeader key={col.id} column={col} sort={sort} onSort={(next) => setQuery({ sort: next, page: null })}
                      width={widthOf(col.id, widths)} onResize={(px) => resizeColumn(col.id, px)} onReset={() => resetColumn(col.id)} />
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.items.map((v) => (
                  <tr key={v.id} aria-selected={selected.includes(v.id)}>
                    <td class="check-col">
                      <input type="checkbox" aria-label={`Select ${v.title}`} checked={selected.includes(v.id)}
                        onChange={() => setSelected(toggle(selected, v.id))} />
                    </td>
                    {visible.map((col) => (
                      <td key={col.id} class={`col-${col.id}${col.numeric ? " numeric" : ""}`}>
                        {col.id === "title" ? (
                          <div class="title-cell">
                            <button type="button" class="row-open" title={v.title} onClick={() => setQuery({ open: v.id })}>
                              {col.render(v)}
                            </button>
                            <FitBadge fit={fits[v.id]} />
                          </div>
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
                <FitBadge fit={fits[v.id]} />
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
              <Icon name="chevron-left" />
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
              <Icon name="chevron-right" />
            </button>
          </nav>
        </>
      )}

      {loading && !data && <p class="muted" role="status">Loading vacancies…</p>}

      {toast && (
        <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)}
          action={toast.undo ? { label: "Undo", onClick: () => { const undo = toast.undo; setToast(null); undo?.(); } } : undefined} />
      )}

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

/** The table is at least as wide as its columns, so a narrow window scrolls sideways instead of squeezing them. */
export function tableMinWidth(columns: Column[], widths: Widths): number {
  return CHECK_WIDTH + columns.reduce((sum, c) => sum + (widthOf(c.id, widths) ?? TITLE_ROOM), 0);
}

interface HeaderProps {
  column: Column;
  sort: string;
  onSort: (next: string) => void;
  width: number | undefined;
  onResize: (px: number) => void;
  onReset: () => void;
}

function SortableHeader({ column, sort, onSort, width, onResize, onReset }: HeaderProps) {
  const cell = useRef<HTMLTableCellElement>(null);
  const resizer = (
    <ColumnResizer
      label={column.label}
      width={() => cell.current?.offsetWidth || width || TITLE_ROOM}
      onResize={onResize}
      onReset={onReset}
    />
  );
  const classes = `col-${column.id}${column.numeric ? " numeric" : ""}`;
  if (!column.sortKey) {
    return <th scope="col" class={classes} ref={cell}>{column.label}{resizer}</th>;
  }
  const [currentKey, direction] = sort.split(":");
  const active = currentKey === column.sortKey;
  const nextDirection = active && direction === "asc" ? "desc" : "asc";
  const ariaSort = active ? (direction === "asc" ? "ascending" : "descending") : "none";
  return (
    <th scope="col" aria-sort={ariaSort} class={classes} ref={cell}>
      <button type="button" class="sort-button" onClick={() => onSort(`${column.sortKey}:${nextDirection}`)}>
        {column.label}
        <span aria-hidden="true">{active ? (direction === "asc" ? " ▲" : " ▼") : ""}</span>
      </button>
      {resizer}
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
      <summary class="btn">
        <Icon name="columns" />
        Columns
      </summary>
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
