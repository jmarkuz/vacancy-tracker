/** Column widths of the Pipeline table, in pixels, set by dragging the edge of a header. Kept per browser. */
export type Widths = Record<string, number>;

export const MIN_WIDTH = 60;
export const MAX_WIDTH = 800;
/** How far one arrow key press moves an edge. */
export const KEY_STEP = 16;
export const STORAGE_KEY = "vt:pipeline-column-widths";

/** Width a column has until the user drags it. The title has none: it takes what the others leave. */
const DEFAULTS: Widths = {
  company: 170,
  status: 120,
  location: 170,
  workMode: 110,
  salary: 150,
  priority: 90,
  nextActionAt: 185,
  lastSeenAt: 185,
  postedAt: 185,
  tags: 160,
};

export const clampWidth = (px: number): number => Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, Math.round(px)));

/** The width a column shows: the user's, else its default, else undefined (automatic). */
export const widthOf = (id: string, widths: Widths): number | undefined => widths[id] ?? DEFAULTS[id];

/** Reads stored widths; anything that is not a sensible number is dropped. */
export function parseWidths(raw: string | null): Widths {
  if (!raw) {
    return {};
  }
  try {
    const value: unknown = JSON.parse(raw);
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
      return {};
    }
    const out: Widths = {};
    for (const [id, px] of Object.entries(value)) {
      if (typeof px === "number" && Number.isFinite(px)) {
        out[id] = clampWidth(px);
      }
    }
    return out;
  } catch {
    return {};
  }
}

export const withWidth = (widths: Widths, id: string, px: number): Widths => ({ ...widths, [id]: clampWidth(px) });

/** Back to the default for one column. */
export function withoutWidth(widths: Widths, id: string): Widths {
  const { [id]: _dropped, ...rest } = widths;
  return rest;
}

export function loadWidths(storage: Pick<Storage, "getItem"> | undefined = safeStorage()): Widths {
  try {
    return parseWidths(storage?.getItem(STORAGE_KEY) ?? null);
  } catch {
    return {};
  }
}

export function saveWidths(widths: Widths, storage: Pick<Storage, "setItem"> | undefined = safeStorage()): void {
  try {
    storage?.setItem(STORAGE_KEY, JSON.stringify(widths));
  } catch {
    // Private mode or a full store: the widths then last until the page is closed.
  }
}

function safeStorage(): Storage | undefined {
  try {
    return window.localStorage;
  } catch {
    return undefined;
  }
}
