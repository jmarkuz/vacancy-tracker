import { useEffect, useRef, useState } from "preact/hooks";
import { ApiError, getJson } from "../api/client";
import type { PageResponse, Vacancy } from "../api/types";
import { changeStatus } from "../api/vacancies";
import { actionForKey, isTypingTarget, moveSelection, selectionAfterRemoval } from "../domain/triage";
import { Toast } from "../components/Toast";
import { setQuery } from "../router";
import { formatDate } from "./columns";
import { VacancyDrawer } from "./VacancyDrawer";

const PAGE_SIZE = 100;

interface Props {
  params: URLSearchParams;
}

/**
 * Inbox (T-048): New vacancies, triaged from the keyboard. J and K move, S saves, X dismisses, O opens the
 * original, Enter opens details. Shortcuts are off while typing in a field or while the drawer is open.
 */
export function Inbox({ params }: Props) {
  const [items, setItems] = useState<Vacancy[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState(-1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<{ message: string; tone: "info" | "error" } | null>(null);
  const [reload, setReload] = useState(0);
  const listRef = useRef<HTMLUListElement>(null);
  const openId = params.get("open");

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    getJson<PageResponse<Vacancy>>(
      `/api/vacancies?status=New&sort=lastSeenAt:desc&size=${PAGE_SIZE}`,
      controller.signal,
    )
      .then((page) => {
        setItems(page.items);
        setTotal(page.total);
        setSelected(page.items.length > 0 ? 0 : -1);
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
  }, [reload]);

  const triage = async (vacancy: Vacancy, status: "Saved" | "Dismissed", index: number) => {
    try {
      await changeStatus(vacancy.id, status);
    } catch (e: unknown) {
      setToast({ message: e instanceof Error ? e.message : `Could not mark it ${status}.`, tone: "error" });
      return;
    }
    const remaining = items.filter((v) => v.id !== vacancy.id);
    setItems(remaining);
    setTotal((t) => Math.max(0, t - 1));
    setSelected(selectionAfterRemoval(index, remaining.length));
    setToast({ message: `${status}: ${vacancy.title}`, tone: "info" });
    if (remaining.length === 0) {
      setReload((n) => n + 1);
    }
  };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (openId || isTypingTarget(e.target)) {
        return;
      }
      const action = actionForKey(e.key, { ctrl: e.ctrlKey, meta: e.metaKey, alt: e.altKey });
      if (!action) {
        return;
      }
      const current = items[selected];
      if (action === "next" || action === "previous") {
        e.preventDefault();
        setSelected(moveSelection(selected, action === "next" ? 1 : -1, items.length));
        return;
      }
      if (!current) {
        return;
      }
      if (action === "save" || action === "dismiss") {
        e.preventDefault();
        triage(current, action === "save" ? "Saved" : "Dismissed", selected);
      } else if (action === "open-original") {
        if (current.url) {
          window.open(current.url, "_blank", "noopener,noreferrer");
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
  });

  // Keep the selected row in view while moving with the keyboard.
  useEffect(() => {
    const row = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]');
    row?.scrollIntoView?.({ block: "nearest" });
  }, [selected]);

  const activeId = items[selected] ? `inbox-${items[selected].id}` : undefined;

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
          {items.map((v, i) => (
            <li
              key={v.id}
              id={`inbox-${v.id}`}
              role="option"
              aria-selected={i === selected}
              class={`inbox-row${i === selected ? " selected" : ""}`}
              onClick={() => setSelected(i)}
              onDblClick={() => setQuery({ open: v.id })}
            >
              <span class="inbox-title">{v.title}</span>
              <span class="muted">{v.company}</span>
              <span class="muted">{v.location ?? "-"}</span>
              <span class="muted num small">{formatDate(v.lastSeenAt)}</span>
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
