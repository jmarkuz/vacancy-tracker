import { useEffect, useState } from "preact/hooks";

export const SCREENS = ["dashboard", "inbox", "pipeline", "resume", "sources", "profiles", "export"] as const;
export type Screen = (typeof SCREENS)[number];

export interface Route {
  screen: Screen;
  params: URLSearchParams;
}

/** Parses "#/pipeline?sort=title:asc" into a screen and its query parameters. */
export function parseRoute(hash: string): Route {
  const [path, query = ""] = hash.replace(/^#\/?/, "").split("?", 2);
  const screen = (SCREENS as readonly string[]).includes(path) ? (path as Screen) : "dashboard";
  return { screen, params: new URLSearchParams(query) };
}

/** Current route; re-renders on every hash change. Filter and sort state lives in the URL. */
export function useRoute(): Route {
  const [route, setRoute] = useState<Route>(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}

/** Updates query parameters of the current screen; null removes a parameter. */
export function setQuery(patch: Record<string, string | null>): void {
  const route = parseRoute(window.location.hash);
  for (const [key, value] of Object.entries(patch)) {
    if (value === null) {
      route.params.delete(key);
    } else {
      route.params.set(key, value);
    }
  }
  const query = route.params.toString();
  window.location.hash = `#/${route.screen}${query ? `?${query}` : ""}`;
}
