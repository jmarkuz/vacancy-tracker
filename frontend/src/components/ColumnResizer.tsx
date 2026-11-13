import { useRef } from "preact/hooks";
import { KEY_STEP, MAX_WIDTH, MIN_WIDTH } from "../domain/columnWidths";

interface Props {
  label: string;
  /** Current width of the column in pixels (measured when it is automatic). */
  width: () => number;
  onResize: (px: number) => void;
  onReset: () => void;
}

/**
 * The draggable edge on the right of a column header. Drag with a mouse, finger or pen; focus it and use the
 * arrow keys; double-click or press Enter to go back to the default width.
 */
export function ColumnResizer({ label, width, onResize, onReset }: Props) {
  const drag = useRef<{ x: number; start: number } | null>(null);

  const down = (e: PointerEvent) => {
    e.preventDefault();
    e.stopPropagation();
    drag.current = { x: e.clientX, start: width() };
    (e.currentTarget as HTMLElement).setPointerCapture?.(e.pointerId);
  };
  const move = (e: PointerEvent) => {
    if (drag.current) {
      onResize(drag.current.start + e.clientX - drag.current.x);
    }
  };
  const up = () => {
    drag.current = null;
  };
  const key = (e: KeyboardEvent) => {
    if (e.key === "ArrowLeft" || e.key === "ArrowRight") {
      e.preventDefault();
      onResize(width() + (e.key === "ArrowRight" ? KEY_STEP : -KEY_STEP));
    } else if (e.key === "Enter") {
      e.preventDefault();
      onReset();
    }
  };

  return (
    <span
      class="col-resizer"
      role="separator"
      aria-orientation="vertical"
      aria-label={`Resize ${label} column`}
      aria-valuemin={MIN_WIDTH}
      aria-valuemax={MAX_WIDTH}
      aria-valuenow={Math.round(width())}
      tabIndex={0}
      title="Drag to resize, double-click to reset"
      onPointerDown={down}
      onPointerMove={move}
      onPointerUp={up}
      onPointerCancel={up}
      onClick={(e) => e.stopPropagation()}
      onDblClick={onReset}
      onKeyDown={key}
    />
  );
}
