import { useEffect, useRef, useState } from "preact/hooks";
import { logFailure } from "./api/logFailure";
import { getFits, type ResumeFit } from "./api/resume";
import { fitsById } from "./domain/fit";

/**
 * The fit to the resume of the rows on show (T-081), asked for apart from the list so the list never waits for it (the
 * server may have to score postings it has not scored yet). Only rows not asked for before are requested, so a list
 * that shrinks after a triage, or reloads with the same rows, makes no request; a failed request is retried at the next
 * change. The result is empty when no resume is uploaded.
 */
export function useFits(ids: string[]): Record<string, ResumeFit> {
  const [fits, setFits] = useState<Record<string, ResumeFit>>({});
  const asked = useRef(new Set<string>());
  const key = ids.join(",");

  useEffect(() => {
    const missing = ids.filter((id) => !asked.current.has(id));
    if (missing.length === 0) return;
    missing.forEach((id) => asked.current.add(id));
    const controller = new AbortController();
    let done = false;
    getFits(missing, controller.signal)
      .then((list) => {
        done = true;
        setFits((now) => ({ ...now, ...fitsById(list) }));
      })
      .catch((error: unknown) => {
        done = true;
        missing.forEach((id) => asked.current.delete(id));
        logFailure("the fit scores", controller.signal)(error);
      });
    return () => {
      // Rows whose answer did not arrive before the list changed are asked for again by the next run.
      if (!done) missing.forEach((id) => asked.current.delete(id));
      controller.abort();
    };
  }, [key]);

  return fits;
}
