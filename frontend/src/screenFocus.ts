import { useEffect, useRef } from "preact/hooks";

export const APP_TITLE = "Vacancy Tracker";

/**
 * Names the page in the tab title and, when the screen changes, moves focus to the content so a keyboard or
 * screen-reader user lands on the new screen. Not on the first load, and not when only the query changes.
 */
export function useScreenAnnouncement(screen: string, title: string, ready: boolean): void {
  const shown = useRef<string | null>(null);
  useEffect(() => {
    if (!ready) {
      return;
    }
    document.title = `${title} - ${APP_TITLE}`;
    if (shown.current !== null && shown.current !== screen) {
      document.getElementById("content")?.focus();
    }
    shown.current = screen;
  }, [screen, title, ready]);
}
