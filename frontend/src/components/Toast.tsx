import { useEffect, useRef } from "preact/hooks";

interface Props {
  message: string;
  tone: "info" | "error";
  /** Optional link, for example to the Inbox after a refresh. */
  link?: { href: string; label: string };
  onDismiss: () => void;
}

/** How long an informational message stays. Errors stay until dismissed, so they cannot be missed. */
export const INFO_TOAST_MS = 6000;

/** Non-blocking message. Announced politely; the dismiss button is always reachable. */
export function Toast({ message, tone, link, onDismiss }: Props) {
  const dismiss = useRef(onDismiss);
  dismiss.current = onDismiss;

  useEffect(() => {
    if (tone !== "info") {
      return undefined;
    }
    const timer = setTimeout(() => dismiss.current(), INFO_TOAST_MS);
    return () => clearTimeout(timer);
  }, [message, tone]);

  return (
    <div class={`toast toast-${tone}`} role={tone === "error" ? "alert" : "status"}>
      <span>{message}</span>
      {link && <a href={link.href}>{link.label}</a>}
      <button type="button" class="btn ghost" onClick={onDismiss} aria-label="Dismiss message">
        ×
      </button>
    </div>
  );
}
