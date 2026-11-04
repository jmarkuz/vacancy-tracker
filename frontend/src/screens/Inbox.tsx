import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError, getJson } from "../api/client";
import type { PageResponse, Vacancy } from "../api/types";
import { changeStatus } from "../api/vacancies";
import { postedText } from "../domain/age";
import { GhostBadge } from "../components/GhostBadge";
import { safeLink } from "../domain/links";
import { getFits, type ResumeFit } from "../api/resume";
import { fitsById } from "../domain/fit";
import { FitBadge } from "../components/FitBadge";
import { actionForKey, activatesOnKey, isTypingTarget, moveSelection, selectionAfterRemoval } from "../domain/triage";
import { Toast } from "../components/Toast";
import { setQuery } from "../router";
import { serializeCriteria } from "../domain/criteria";
import { EMPTY_FILTER, filterFromParams, filterToParams, inboxCriteria, type InboxFilter, isActive } from "../domain/inboxFilter";
import { formatDate } from "./columns";
import { VacancyDrawer } from "./VacancyDrawer";
import { InboxInspector } from "../components/InboxInspector";

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
 * The filter above the list (T-100) narrows the Inbox to New vacancies whose title or description has the given
 * words, on the server; it is kept in the address, and "/" jumps to it.
 */
export function Inbox({ params, dataVersion = 0 }: Props) {
  const [items, setItems] = useState<Vacancy[]>([]);
  const [total, setTotal] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; tone: "info" | "error" } | null>(null);
  const [reload, setReload] = useState(0);
  // The vacancy whose save or skip is on its way; its buttons wait for the server.
  const [pendingId, setPendingId] = useState<string | null>(null);
  // Grows each time the full details close: the inspector reloads what may have been saved there.
  const [detailsClosed, setDetailsClosed] = useState(0);
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
  // What is typed; it reaches the address (and so the list) a moment after typing stops.
  const applied = filterFromParams(params);
  const [draft, setDraft] = useState<InboxFilter>(applied);
  const filter = serializeCriteria(inboxCriteria(applied));
  const searchRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const timer = window.setTimeout(() => {
      const next = filterToParams(draft);
      const now = filterToParams(filterFromParams(params));
      if (JSON.stringify(next) !== JSON.stringify(now)) {
        setQuery(next);
      }
    }, 350);
    return () => window.clearTimeout(timer);
  }, [draft.words, draft.mode, draft.exclude]);

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
      `/api/vacancies?status=New&sort=lastSeenAt:desc&size=${PAGE_SIZE}${filter ? `&filter=${encodeURIComponent(filter)}` : ""}`,
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
  }, [reload, dataVersion, filter]);

  const triage = async (vacancy: Vacancy, status: "Saved" | "Dismissed") => {
    if (triaging.current.has(vacancy.id)) {
      setToast({ message: "The previous change of this vacancy is still being saved.", tone: "info" });
      return;
    }
    triaging.current.add(vacancy.id);
    setPendingId(vacancy.id);
    try {
      await changeStatus(vacancy.id, status);
    } catch (e: unknown) {
      triaging.current.delete(vacancy.id);
      setPendingId(null);
      setToast({ message: e instanceof Error ? e.message : `Could not mark it ${status}.`, tone: "error" });
      return;
    }
    triaging.current.delete(vacancy.id);
    setPendingId(null);
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
      if (e.key === "/" && !e.ctrlKey && !e.metaKey && !e.altKey) {
        e.preventDefault();
        searchRef.current?.focus();
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
        <span class="muted small num">
          {total.toLocaleString()} new{isActive(applied) ? " matching the filter" : ""}
        </span>
      </div>

      <form class="inbox-filter" role="search" aria-label="Filter the Inbox" onSubmit={(e) => e.preventDefault()}>
        <input
          ref={searchRef}
          class="input grow"
          type="search"
          aria-label="Words in the title or description"
          placeholder='Words in the title or description, e.g. java remote "spring boot"'
          value={draft.words}
          maxLength={500}
          onInput={(e) => setDraft({ ...draft, words: (e.target as HTMLInputElement).value })}
          onKeyDown={(e) => {
            if (e.key === "Escape") {
              (e.target as HTMLInputElement).blur();
              listRef.current?.focus();
            }
          }}
        />
        <select class="input" aria-label="How the words must match" value={draft.mode}
          onChange={(e) => setDraft({ ...draft, mode: (e.target as HTMLSelectElement).value === "any" ? "any" : "all" })}>
          <option value="all">All words</option>
          <option value="any">Any word</option>
        </select>
        <input
          class="input"
          type="search"
          aria-label="Words to exclude"
          placeholder="Exclude, e.g. crypto"
          value={draft.exclude}
          maxLength={500}
          onInput={(e) => setDraft({ ...draft, exclude: (e.target as HTMLInputElement).value })}
        />
        {isActive(draft) && (
          <button class="btn ghost" type="button" onClick={() => setDraft(EMPTY_FILTER)}>
            Clear
          </button>
        )}
      </form>

      <p class="shortcut-legend muted">
        <kbd>J</kbd>/<kbd>K</kbd> move · <kbd>S</kbd> save · <kbd>X</kbd> dismiss · <kbd>O</kbd> open original ·{" "}
        <kbd>Enter</kbd> details · <kbd>/</kbd> filter
      </p>

      {error && (
        <div class="notice error" role="alert">
          <span>{error}</span>
          <button class="btn" type="button" onClick={() => setReload((n) => n + 1)}>
            Retry
          </button>
        </div>
      )}

      {!error && !loading && items.length === 0 && !isActive(applied) && (
        <div class="panel empty-state">
          <h2>Inbox is empty</h2>
          <p class="muted">Refresh the sources to look for new vacancies.</p>
        </div>
      )}
      {!error && !loading && items.length === 0 && isActive(applied) && (
        <div class="panel empty-state">
          <h2>No new vacancy matches the filter</h2>
          <button class="btn" type="button" onClick={() => setDraft(EMPTY_FILTER)}>
            Clear the filter
          </button>
        </div>
      )}

      {loading && items.length === 0 && <p class="muted" role="status">Loading the Inbox…</p>}

      {items.length > 0 && (
        <div class="inbox-layout">
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
              <span class="inbox-main">
                <span class="inbox-title">{v.title}</span>
                <span class="muted small">
                  {[v.company, v.location ?? "Location not specified", v.workMode].filter(Boolean).join(" · ")}
                  {v.postedAt ? ` · posted ${postedText(v.postedAt, new Date()).toLowerCase()}` : ""} · seen{" "}
                  <span class="num">{formatDate(v.lastSeenAt)}</span>
                </span>
              </span>
              <span class="inbox-actions">
                <GhostBadge ghost={v.ghost} />
                <FitBadge fit={fits[v.id]} />
              </span>
            </li>
          ))}
        </ul>
        {selected >= 0 && (
          <InboxInspector
            vacancy={items[selected]}
            fit={fits[items[selected].id]}
            busy={pendingId === items[selected].id}
            refreshKey={detailsClosed}
            onSave={() => triage(items[selected], "Saved")}
            onSkip={() => triage(items[selected], "Dismissed")}
            onDetails={() => setQuery({ open: items[selected].id })}
          />
        )}
        </div>
      )}

      {openId && (
        <VacancyDrawer
          id={openId}
          onClose={() => {
            setQuery({ open: null });
            setDetailsClosed((n) => n + 1);
          }}
          onChange={() => setReload((n) => n + 1)}
        />
      )}

      {toast && <Toast message={toast.message} tone={toast.tone} onDismiss={() => setToast(null)} />}
    </section>
  );
}
