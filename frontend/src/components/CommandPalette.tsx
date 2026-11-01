import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { searchVacancies, type SearchHit } from "../api/search";
import { matchCommands, splitSnippet, stepSelection } from "../domain/palette";

interface Props {
  onClose: () => void;
}

interface Row {
  key: string;
  href: string;
  label: string;
  detail?: string;
  snippet?: string;
}

/** Command palette (T-062): opens with Ctrl/Cmd+K, jumps to a screen or to a vacancy found by full-text search. */
export function CommandPalette({ onClose }: Props) {
  const [query, setQuery] = useState("");
  const [hits, setHits] = useState<SearchHit[]>([]);
  const [failed, setFailed] = useState(false);
  const [index, setIndex] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const opener = useRef<Element | null>(document.activeElement);

  useEffect(() => {
    input.current?.focus();
    return () => (opener.current as HTMLElement | null)?.focus?.();
  }, []);

  useEffect(() => {
    setIndex(0);
    if (query.trim() === "") {
      setHits([]);
      setFailed(false);
      return;
    }
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      searchVacancies(query, controller.signal)
        .then((result) => {
          setHits(result);
          setFailed(false);
        })
        .catch(() => {
          if (!controller.signal.aborted) {
            setFailed(true);
          }
        });
    }, 200);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  const rows: Row[] = useMemo(() => [
    ...matchCommands(query).map((c) => ({ key: `go-${c.id}`, href: c.href, label: c.label, detail: "Go to" })),
    ...hits.map((h) => ({ key: h.id, href: `#/pipeline?open=${h.id}`, label: h.title, detail: `${h.company} · ${h.status}`, snippet: h.snippet })),
  ], [query, hits]);

  const go = (row: Row | undefined) => {
    if (row) {
      window.location.hash = row.href;
      onClose();
    }
  };

  const onKey = (e: KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    } else if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      setIndex((i) => stepSelection(i, e.key === "ArrowDown" ? 1 : -1, rows.length));
    } else if (e.key === "Enter") {
      e.preventDefault();
      go(rows[index]);
    } else if (e.key === "Tab") {
      e.preventDefault(); // The palette is one control; focus stays in the box.
    }
  };

  return (
    <div class="palette-backdrop" onMouseDown={(e) => { if (e.target === e.currentTarget) { onClose(); } }}>
      <div class="palette" role="dialog" aria-modal="true" aria-label="Command palette" onKeyDown={onKey}>
        <input ref={input} class="input palette-input" type="text" role="combobox" aria-expanded="true" aria-controls="palette-list"
          aria-activedescendant={rows[index] ? `palette-${rows[index].key}` : undefined} aria-label="Search vacancies or go to a screen"
          placeholder="Search vacancies, or type a screen name" value={query} onInput={(e) => setQuery((e.target as HTMLInputElement).value)} />
        <ul id="palette-list" class="palette-list" role="listbox" aria-label="Results">
          {rows.map((row, i) => (
            <li key={row.key} id={`palette-${row.key}`} role="option" aria-selected={i === index} class={i === index ? "selected" : undefined}
              onMouseMove={() => setIndex(i)} onMouseDown={(e) => { e.preventDefault(); go(row); }}>
              <div><strong>{row.label}</strong> <span class="muted small">{row.detail}</span></div>
              {row.snippet && (
                <div class="muted small palette-snippet">
                  {splitSnippet(row.snippet).map((p, n) => (p.match ? <mark key={n}>{p.text}</mark> : <span key={n}>{p.text}</span>))}
                </div>
              )}
            </li>
          ))}
        </ul>
        <p class="muted small" role="status" aria-live="polite">
          {failed ? "Search is not available right now." : query.trim() !== "" && hits.length === 0 ? "No vacancies match." : ""}
        </p>
      </div>
    </div>
  );
}
