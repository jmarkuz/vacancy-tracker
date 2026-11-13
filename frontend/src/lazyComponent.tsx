import type { ComponentType } from "preact";
import { useEffect, useState } from "preact/hooks";
import { LoadFailed } from "./components/LoadFailed";

/**
 * Loads a screen's code when it is first opened, so the first page carries only the screens everyone uses (UI speed
 * review, 2026-11-13). Once loaded the component is kept, so going back to a screen shows it at once. While the code
 * arrives a status line is shown; if it cannot arrive (offline, or a new release replaced the files the old page asks
 * for) the user gets "Try again" instead of a blank screen.
 */
export function lazyComponent<P extends object>(
  load: () => Promise<ComponentType<P>>,
  what: string,
): ComponentType<P> {
  let loaded: ComponentType<P> | null = null;
  let pending: Promise<void> | null = null;

  const start = (): Promise<void> => {
    pending ??= load().then(
      (component) => {
        loaded = component;
      },
      (error: unknown) => {
        pending = null; // so "Try again" asks the network again
        throw error;
      },
    );
    return pending;
  };

  return function Lazy(props: P) {
    const [, setTick] = useState(0);
    const [failed, setFailed] = useState(false);
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
      if (loaded) return;
      let live = true;
      setFailed(false);
      start().then(
        () => live && setTick((n) => n + 1),
        () => live && setFailed(true),
      );
      return () => {
        live = false;
      };
    }, [attempt]);

    if (loaded) {
      const Component = loaded;
      return <Component {...props} />;
    }
    if (failed) return <LoadFailed what={what} onRetry={() => setAttempt((n) => n + 1)} />;
    return (
      <p class="muted" role="status">
        Loading {what}…
      </p>
    );
  };
}
