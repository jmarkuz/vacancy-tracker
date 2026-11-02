import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError, getJson } from "../api/client";
import type { PageResponse, Vacancy } from "../api/types";
import { changeStatus } from "../api/vacancies";
import { safeLink } from "../domain/links";
import { getFits, type ResumeFit } from "../api/resume";
import { fitsById } from "../domain/fit";
import { FitBadge } from "../components/FitBadge";
import { actionForKey, activatesOnKey, isTypingTarget, moveSelection, selectionAfterRemoval } from "../domain/triage";
import { Toast } from "../components/Toast";
import { setQuery } from "../router";
import { formatDate } from "./columns";
import { VacancyDrawer } from "./VacancyDrawer";

const PAGE_SIZE = 100;

interface Props {
  params: URLSearchParams;
  /** Grows when a refresh stored new data: the list loads again and the selected vacancy stays selected. */
  dataVersion?: number;
}

/**
 * Inbox (T-048): New vacancies, triaged from the keyboard. J and K move, S saves, X dismisses, O opens the
 * original, Enter opens details. Shortcuts are off while typing in a field or while the drawer is open.
 * The selection is a vacancy id, not a position: a key acts on the row that is highlighted when it is pressed,
 * however many rows a finished request has removed meanwhile (review 2026-11-02).
 */
export function Inbox({ params, dataVersion = 0 }: Props) {
  const [items, setItems] = useState<Vacancy[]>([]);
  const [total, setTotal] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; tone: "info" | "error" } | null>(null);
  const [reload, setReload] = useState(0);
  const listRef = useRef<HTMLUListElement>(null);
  const triaging = useRef(new Set<string>());
  const itemsRef = useRef<Vacancy[]>([]);
  itemsRef.current = items;
  const selectedRef = useRef<string | null>(null);
  selectedRef.current = selectedId;
  const openId = params.get("open");
  const openRef = useRef(openId);
  openRef.current = openId;
  const selected = items.findIndex((v) => v.id === selectedId);
  const [fits, setFits] = useState<Record<string, ResumeFit>>({});

  // Fit to the resume for the rows on show (T-081); none when no resume is uploaded.
  useEffect(() => {
    const controller = new AbortController();
    getFits(items.map((v) => v.id), controller.signal)
      .then((list) => setFits(fitsById(list)))
      .catch(() => undefined);
    return () => controller.abort();
  }, [items.map((v) => v.id).join(",")]);

  const select = (id: string | null) => {
    selectedRef.current = id;
    setSelectedId(id);
  };

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    getJson<PageResponse<Vacancy>>(
      `/api/vacancies?status=New&sort=lastSeenAt:desc&size=${PAGE_SIZE}`,
      controller.signal,
    )
      .then((page) => {
        setItems(page.items);
        itemsRef.current = page.items;
        setTotal(page.total);
        const keep = page.items.some((v) => v.id === selectedRef.current);
        select(keep ? selectedRef.current : (page.items[0]?.id ?? null));
        setError(null);
        setLoading(false);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted) {
          setError(e instanceof ApiError ? e.message : "Could not load the Inbox.");
          setLoading(false);
        }
      });
    return () => controller.abort();
  }, [reload, dataVersion]);

  const triage = async (vacancy: Vacancy, status: "Saved" | "Dismissed") => {
    if (triaging.current.has(vacancy.id)) {
      setToast({ message: "The previous change of this vacancy is still being saved.", tone: "info" });
      return;
    }
    triaging.current.add(vacancy.id);
    try {
      await changeStatus(vacancy.id, status);
    } catch (e: unknown) {
      triaging.current.delete(vacancy.id);
      setToast({ message: e instanceof Error ? e.message : `Could not mark it ${status}.`, tone: "error" });
      return;
    }
    triaging.current.delete(vacancy.id);
    // The list as it is now: other rows may have been triaged while this request was in flight.
    const before = itemsRef.current;
    const remaining = before.filter((v) => v.id !== vacancy.id);
    itemsRef.current = remaining;
    setItems(remaining);
    setTotal((t) => Math.max(0, t - 1));
    // Only when the removed row is still the highlighted one does the highlight move, to the row that took its place.
    if (selectedRef.current === vacancy.id) {
      const index = selectionAfterRemoval(before.findIndex((v) => v.id === vacancy.id), remaining.length);
      select(index < 0 ? null : remaining[index].id);
    }
    setToast({ message: `${status}: ${vacancy.title}`, tone: "info" });
    if (remaining.length === 0) {
      setReload((n) => n + 1);
    }
  };
  const triageRef = useRef(triage);
  triageRef.current = triage;

  // Registered once and reading refs, so a key pressed right after another sees the selection that one made.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (openRef.current || e.repeat || isTypingTarget(e.target) || activatesOnKey(e.target, e.key)) {
        return;
      }
      const action = actionForKey(e.key, { ctrl: e.ctrlKey, meta: e.metaKey, alt: e.altKey }, e.code);
      if (!action) {
        return;
      }
      const list = itemsRef.current;
      const index = list.findIndex((v) => v.id === selectedRef.current);
      const current = index >= 0 ? list[index] : undefined;
      if (action === "next" || action === "previous") {
        e.preventDefault();
        const next = moveSelection(index, action === "next" ? 1 : -1, list.length);
        select(next < 0 ? null : list[next].id);
        return;
      }
      if (!current) {
        return;
      }
      if (action === "save" || action === "dismiss") {
        e.preventDefault();
        triageRef.current(current, action === "save" ? "Saved" : "Dismissed");
      } else if (action === "open-original") {
        const link = safeLink(current.url);
        if (link) {
          window.open(link, "_blank", "noopener,noreferrer");
        } else {
          setToast({ message: "This vacancy has no original link.", tone: "info" });
        }
      } else if (action === "open-details") {
        e.preventDefault();
        setQuery({ open: current.id });
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  // Keep the selected row in view while moving with the keyboard.
  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]');
    row?.scrollIntoView?.({ block: "nearest" });
  }, [selectedId]);

  const activeId = selected >= 0 ? `inbox-${items[selected].id}` : undefined;

  return (
    <section aria-labelledby="inbox-title">
      <div class="toolbar">
        <h1 id="inbox-title">Inbox</h1>
        <span class="grow" />
        <span class="muted small num">{total.toLocaleString()} new</span>
      </div>

      <p class="shortcut-legend muted">
        <kbd>J</kbd>/<kbd>K</kbd> move · <kbd>S</kbd> save · <kbd>X</kbd> dismiss · <kbd>O</kbd> open original ·{" "}
        <kbd>Enter</kbd> details
      </p>

      {error && (
        <div class="notice error" role="alert">
          <span>{error}</span>
          <button class="btn" type="button" onClick={() => setReload((n) => n + 1)}>
            Retry
          </button>
        </div>
      )}

      {!error && !loading && items.length === 0 && (
        <div class="panel empty-state">
          <h2>Inbox is empty</h2>
          <p class="muted">Refresh the sources to look for new vacancies.</p>
        </div>
      )}

      {loading && items.length === 0 && <p class="muted" role="status">Loading the Inbox…</p>}

      {items.length > 0 && (
        <ul
          class="inbox-list"
          role="listbox"
          aria-label="New vacancies"
          aria-activedescendant={activeId}
          tabIndex={0}
          ref={listRef}
        >
          {items.map((v) => (
            <li
              key={v.id}
              id={`inbox-${v.id}`}
              role="option"
              aria-selected={v.id === selectedId}
              class={`inbox-row${v.id === selectedId ? " selected" : ""}`}
              onClick={() => select(v.id)}
              // Tabbing to a row's buttons selects the row, so S and X act on the row that has the focus ring.
              onFocusIn={() => select(v.id)}
              onDblClick={() => setQuery({ open: v.id })}
            >
              <span class="inbox-title">
                {v.title} <FitBadge fit={fits[v.id]} />
              </span>
              <span class="muted">{v.company}</span>
              <span class="muted">{v.location ?? "-"}</span>
              <span class="muted num small">{formatDate(v.lastSeenAt)}</span>
              <span class="inbox-actions">
                <button type="button" class="btn" onClick={(e) => { e.stopPropagation(); triage(v, "Saved"); }}>Save</button>
                <button type="button" class="btn" onClick={(e) => { e.stopPropagation(); triage(v, "Dismissed"); }}>Dismiss</button>
                <button type="button" class="btn ghost" onClick={(e) => { e.stopPropagation(); setQuery({ open: v.id }); }}>Details</button>
              </span>
            </li>
          ))}
        </ul>
      )}

      {openId && (
        <VacancyDrawer
          id={openId}
          onClose={() => setQuery({ open: null })}
          onChange={() => setReload((n) => n + 1)}
        />
      )}

      {toast && <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />}
    </section>
  );
}
