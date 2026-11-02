/** Dark is the default (T-090, ADR-046); light and "follow the system" are a toggle away. */
export type Theme = "dark" | "light" | "system";
const KEY = "vt-theme";
const ORDER: Theme[] = ["dark", "light", "system"];

/** Reads the saved theme; storage can be blocked, so every access is guarded. Nothing saved means dark. */
export function readTheme(): Theme {
  try {
    const value = window.localStorage.getItem(KEY);
    return value === "light" || value === "system" || value === "dark" ? value : "dark";
  } catch {
    return "dark";
  }
}

/** The tokens read data-theme: dark by default, light, or system (light only when the system asks for it). */
export function applyTheme(theme: Theme): void {
  document.documentElement.setAttribute("data-theme", theme);
  try {
    window.localStorage.setItem(KEY, theme);
  } catch {
    /* private mode or blocked storage: the theme still applies for this page view */
  }
}

export function nextTheme(theme: Theme): Theme {
  return ORDER[(ORDER.indexOf(theme) + 1) % ORDER.length];
}
