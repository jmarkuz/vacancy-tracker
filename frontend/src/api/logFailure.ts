/**
 * For background loads whose result is optional (fit scores, source names): the screen works without them, but a
 * failure must leave a trace in the console instead of vanishing. A request that was aborted on purpose is not a failure.
 */
export function logFailure(what: string, signal?: AbortSignal): (error: unknown) => void {
  return (error) => {
    if (!signal?.aborted) {
      console.warn(`Could not load ${what}`, error);
    }
  };
}
