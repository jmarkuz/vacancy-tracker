/**
 * Light is the default (Beacon, T-101, ADR-052); dark and "follow the system" are a choice away. The base text
 * size is 18 px and can be raised to 20 or 22 px; both choices are kept in this browser only.
 */
export type Theme = "light" | "dark" | "system";
export type TextSize = "18" | "20" | "22";
const KEY = "vt-theme";
const SIZE_KEY = "vt-text-size";
const ORDER: Theme[] = ["light", "dark", "system"];
export const TEXT_SIZES: TextSize[] = ["18", "20", "22"];

function read(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    return null;
  }
}

function write(key: string, value: string): void {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    /* private mode or blocked storage: the choice still applies for this page view */
  }
}

/** Reads the saved theme; storage can be blocked, so every access is guarded. Nothing saved means light. */
export function readTheme(): Theme {
  const value = read(KEY);
  return value === "dark" || value === "system" || value === "light" ? value : "light";
}

/** The tokens read data-theme: light by default, dark, or system (dark only when the system asks for it). */
export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute("data-theme", theme);
  write(KEY, theme);
}

export function nextTheme(theme: Theme): Theme {
  return ORDER[(ORDER.indexOf(theme) + 1) % ORDER.length];
}

export function readTextSize(): TextSize {
  const value = read(SIZE_KEY);
  return value === "20" || value === "22" ? value : "18";
}

/** The base text size in pixels; every size of the page follows it (data-text-size on the root). */
export function applyTextSize(size: TextSize): void {
  if (size === "18") {
    document.documentElement.removeAttribute("data-text-size");
  } else {
    document.documentElement.setAttribute("data-text-size", size);
  }
  write(SIZE_KEY, size);
}
