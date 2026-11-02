export type Theme = "system" | "light" | "dark";
const KEY = "vt-theme";
const ORDER: Theme[] = ["system", "light", "dark"];

/** Reads the saved theme; storage can be blocked, so every access is guarded. */
export function readTheme(): Theme {
  try {
    const value = window.localStorage.getItem(KEY);
    return value === "light" || value === "dark" ? value : "system";
  } catch {
    return "system";
  }
}

export function applyTheme(theme: Theme): void {
  const root = document.documentElement;
  if (theme === "system") {
    root.removeAttribute("data-theme");
  } else {
    root.setAttribute("data-theme", theme);
  }
  try {
    window.localStorage.setItem(KEY, theme);
  } catch {
    /* private mode or blocked storage: the theme still applies for this page view */
  }
}

export function nextTheme(theme: Theme): Theme {
  return ORDER[(ORDER.indexOf(theme) + 1) % ORDER.length];
}
