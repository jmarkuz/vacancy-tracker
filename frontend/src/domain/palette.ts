export interface Command {
  id: string;
  label: string;
  href: string;
}

/** Where the palette can jump to without searching. Keep in sync with the navigation in App. */
export const GO_TO: Command[] = [
  { id: "dashboard", label: "Dashboard", href: "#/dashboard" },
  { id: "inbox", label: "Inbox", href: "#/inbox" },
  { id: "pipeline", label: "Pipeline", href: "#/pipeline" },
  { id: "board", label: "Pipeline board", href: "#/pipeline?layout=board" },
  { id: "mail", label: "Mail", href: "#/mail" },
  { id: "resume", label: "Resume", href: "#/resume" },
  { id: "sources", label: "Sources & Runs", href: "#/sources" },
  { id: "profiles", label: "Search profiles", href: "#/profiles" },
  { id: "export", label: "Export & import", href: "#/export" },
  { id: "settings", label: "Settings", href: "#/settings" },
];

/** Screens whose name contains every typed word. With no text, all of them. */
export function matchCommands(query: string): Command[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  return GO_TO.filter((c) => words.every((w) => c.label.toLowerCase().includes(w)));
}

export interface Part {
  text: string;
  match: boolean;
}

/** Splits a snippet marked with [[ and ]] into plain and matched parts. Nothing is ever treated as HTML. */
export function splitSnippet(snippet: string): Part[] {
  const parts: Part[] = [];
  for (const [i, piece] of snippet.split(/\[\[|\]\]/).entries()) {
    if (piece !== "") {
      parts.push({ text: piece, match: i % 2 === 1 });
    }
  }
  return parts;
}

/** Whether a key press is the palette shortcut: Ctrl+K, or Cmd+K on a Mac. */
/** `code` covers layouts where the K key types another letter (Ukrainian "л"). */
export function isPaletteShortcut(e: { key: string; code?: string; ctrlKey: boolean; metaKey: boolean; altKey: boolean; shiftKey: boolean }): boolean {
  return (e.key.toLowerCase() === "k" || e.code === "KeyK") && (e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey;
}

/** Moves the highlighted row by delta, wrapping around. -1 for nothing highlighted yet. */
export function stepSelection(index: number, delta: number, count: number): number {
  if (count <= 0) {
    return -1;
  }
  return (((index < 0 && delta < 0 ? 0 : index) + delta) % count + count) % count;
}
