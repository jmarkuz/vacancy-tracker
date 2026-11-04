import { useEffect, useRef, useState } from "preact/hooks";

interface Props {
  message: string;
  tone: "info" | "error";
  /** Optional link, for example to the Inbox after a refresh. */
  link?: { href: string; label: string };
  /** Optional button, for example Undo. A toast with an action stays longer. */
  action?: { label: string; onClick: () => void };
  onDismiss: () => void;
}

/** How long an informational message stays. Errors stay until dismissed, so they cannot be missed. */
export const INFO_TOAST_MS = 6000;
/** An action such as Undo needs time to be found and pressed. */
export const ACTION_TOAST_MS = 15000;

/** Non-blocking message. Announced politely; the dismiss button is always reachable. */
export function Toast({ message, tone, link, action, onDismiss }: Props) {
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;
  // The parent builds a fresh action object on every render; only the label decides the timer, the click is read from a ref.
  const click = useRef(action?.onClick);
  click.current = action?.onClick;
  const hasAction = action !== undefined;
  const label = action?.label;
  // The timer stands still while the pointer or the keyboard focus is on the toast, so Undo cannot vanish under the hand.
  const [paused, setPaused] = useState(false);

  useEffect(() => {
    if (tone !== "info" || paused) {
      return undefined;
    }
    const timer = setTimeout(() => dismiss.current(), hasAction ? ACTION_TOAST_MS : INFO_TOAST_MS);
    return () => clearTimeout(timer);
  }, [message, tone, hasAction, label, paused]);

  return (
    <div class={`toast toast-${tone}`} role={tone === "error" ? "alert" : "status"}
      onMouseEnter={() => setPaused(true)} onMouseLeave={() => setPaused(false)}
      onFocusIn={() => setPaused(true)} onFocusOut={() => setPaused(false)}>
      <span>{message}</span>
      {link && <a href={link.href}>{link.label}</a>}
      {action && <button type="button" class="btn" onClick={() => click.current?.()}>{action.label}</button>}
      <button type="button" class="btn ghost" onClick={onDismiss} aria-label="Dismiss message">
        ×
      </button>
    </div>
  );
}
