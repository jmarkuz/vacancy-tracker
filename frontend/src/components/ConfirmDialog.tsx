import { useEffect, useRef } from "preact/hooks";

interface Props {
  title: string;
  message: string;
  /** The label of the confirming button, for example "Delete". */
  confirmLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}

const FOCUSABLE = "button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])";

/**
 * An in-app confirmation instead of window.confirm: a centred glass card over a scrim. Focus moves to the confirming
 * button, Tab stays inside, Escape or a click on the scrim cancels, and focus returns to where it was when it closes.
 */
export function ConfirmDialog({ title, message, confirmLabel, onConfirm, onCancel }: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    confirm.current?.focus();
    return () => {
      if (before?.isConnected) {
        before.focus();
      }
    };
  }, []);

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      onCancel();
      return;
    }
    if (e.key === "Enter" && document.activeElement === confirm.current) {
      // Handled here, so the click the browser would add after this key does not confirm a second time.
      e.preventDefault();
      onConfirm();
      return;
    }
    if (e.key === "Tab" && panel.current) {
      const items = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && document.activeElement === last) {
        e.preventDefault();
        first.focus();
      }
    }
  };

  return (
    <div class="confirm-scrim" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <div
        ref={panel}
        class="confirm-dialog glass"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="confirm-title"
        aria-describedby="confirm-message"
        onKeyDown={(e) => onKeyDown(e as KeyboardEvent)}
      >
        <h2 id="confirm-title">{title}</h2>
        <p id="confirm-message" class="muted">
          {message}
        </p>
        <div class="confirm-actions">
          <button class="btn" type="button" onClick={onCancel}>
            Cancel
          </button>
          <button ref={confirm} class="btn primary" type="button" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
