import { useEffect, useState } from "preact/hooks";

export const SCREENS = ["dashboard", "inbox", "pipeline", "sources", "profiles", "export"] as const;
export type Screen = (typeof SCREENS)[number];

function fromHash(): Screen {
  const name = window.location.hash.replace(/^#\/?/, "");
  return (SCREENS as readonly string[]).includes(name) ? (name as Screen) : "dashboard";
}

/** Hash routing: the server only needs to serve index.html, no fallback rules. */
export function useScreen(): Screen {
  const [screen, setScreen] = useState<Screen>(fromHash);
  useEffect(() => {
    const onChange = () => setScreen(fromHash());
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return screen;
}
