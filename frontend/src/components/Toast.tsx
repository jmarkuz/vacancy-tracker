interface Props {
  message: string;
  tone: "info" | "error";
  /** Optional link, for example to the Inbox after a refresh. */
  link?: { href: string; label: string };
  onDismiss: () => void;
}

/** Non-blocking message. Announced politely; the dismiss button is always reachable. */
export function Toast({ message, tone, link, onDismiss }: Props) {
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
