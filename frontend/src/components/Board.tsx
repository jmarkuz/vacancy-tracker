import { useEffect, useRef, useState } from "preact/hooks";
import type { Vacancy } from "../api/types";
import { allowedNext } from "../domain/statuses";
import { BOARD_COLUMNS, canMove, groupByStatus, moveMessage, neighbourColumn } from "../domain/kanban";

interface Props {
  items: Vacancy[];
  /** Changes the status on the server; rejects with an Error whose message is shown to the user. */
  onMove: (vacancy: Vacancy, status: string) => Promise<void>;
  onOpen: (id: string) => void;
}

/**
 * Pipeline board (T-061). A card moves by drag and drop, by Alt+Left and Alt+Right on the focused card, or with
 * the card's "Move to" list. All three use the same server call, so each one records a status event.
 */
export function Board({ items, onMove, onOpen }: Props) {
  const groups = groupByStatus(items);
  const [dragging, setDragging] = useState<Vacancy | null>(null);
  const [over, setOver] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const [failed, setFailed] = useState(false);
  const refocus = useRef<string | null>(null);

  useEffect(() => {
    const id = refocus.current;
    if (id) {
      refocus.current = null;
      document.querySelector<HTMLElement>(`[data-card="${CSS.escape(id)}"]`)?.focus();
    }
  }, [items]);

  const move = async (v: Vacancy, to: string, keepFocus: boolean) => {
    if (keepFocus) {
      refocus.current = v.id;
    }
    try {
      await onMove(v, to);
      setFailed(false);
      setAnnouncement(`Moved "${v.title}" to ${to}.${BOARD_COLUMNS.includes(to) ? "" : " It is no longer on the board."}`);
    } catch (e: unknown) {
      refocus.current = null;
      setFailed(true);
      setAnnouncement(e instanceof Error ? e.message : `Could not move "${v.title}".`);
    }
  };

  const onKey = (e: KeyboardEvent, v: Vacancy) => {
    if (!e.altKey || (e.key !== "ArrowLeft" && e.key !== "ArrowRight")) {
      return;
    }
    e.preventDefault();
    const target = neighbourColumn(v.status, e.key === "ArrowLeft" ? -1 : 1);
    const result = moveMessage(v.title, v.status, target);
    if (!result.ok || target === null) {
      setFailed(true);
      setAnnouncement(result.text);
      return;
    }
    move(v, target, true);
  };

  return (
    <div class="board-wrap">
      <p class="muted small keyboard-hint">Drag a card to another column, or focus it and press Alt+← / Alt+→. Each card also has a Move to list.</p>
      <p class={failed ? "notice error" : "sr-only"} role={failed ? "alert" : "status"} aria-live="polite">{announcement}</p>
      <div class="board" role="group" aria-label="Pipeline board">
        {BOARD_COLUMNS.map((column) => {
          const droppable = dragging !== null && canMove(dragging.status, column);
          return (
            <section key={column} class={`board-column${droppable ? " droppable" : ""}${over === column && droppable ? " over" : ""}`}
              aria-label={`${column}, ${groups[column].length}`} data-column={column}
              onDragOver={(e) => { if (droppable) { e.preventDefault(); setOver(column); } }}
              onDragLeave={() => setOver((o) => (o === column ? null : o))}
              onDrop={(e) => {
                e.preventDefault();
                setOver(null);
                if (dragging && droppable) {
                  const v = dragging;
                  setDragging(null);
                  move(v, column, false);
                }
              }}>
              <h3>{column} <span class="muted num">{groups[column].length}</span></h3>
              <ul class="board-cards">
                {groups[column].map((v) => (
                  <li key={v.id}>
                    <div class="board-card" tabIndex={0} draggable data-card={v.id}
                      aria-label={`${v.title}, ${v.company}, ${v.status}. Alt+Left or Alt+Right moves it.`}
                      onKeyDown={(e) => onKey(e, v)}
                      onDragStart={(e) => { setDragging(v); e.dataTransfer?.setData("text/plain", v.id); }}
                      onDragEnd={() => { setDragging(null); setOver(null); }}>
                      <button type="button" class="row-open" onClick={() => onOpen(v.id)}>{v.title}</button>
                      <div class="muted small">{v.company}</div>
                      {allowedNext(v.status).length > 0 && (
                        <CardMover key={`${v.id}-${v.status}`} vacancy={v} onMove={(to) => move(v, to, true)} />
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          );
        })}
      </div>
    </div>
  );
}

/** Choosing a column and moving are separate: arrow keys on a closed select would otherwise move the card at once. */
function CardMover({ vacancy, onMove }: { vacancy: Vacancy; onMove: (to: string) => void }) {
  const [to, setTo] = useState("");
  return (
    <div class="card-mover">
      <select class="input" aria-label={`Move ${vacancy.title} to`} value={to}
        onChange={(e) => setTo((e.target as HTMLSelectElement).value)}>
        <option value="">Move to…</option>
        {allowedNext(vacancy.status).map((s) => <option key={s} value={s}>{s}</option>)}
      </select>
      <button type="button" class="btn" disabled={!to} onClick={() => onMove(to)} aria-label={`Move ${vacancy.title}`}>
        Move
      </button>
    </div>
  );
}
