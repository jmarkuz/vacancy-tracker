/** Shown when something could not be read from the server, so an empty panel is never taken for "nothing there". */
export function LoadFailed({ what, onRetry }: { what: string; onRetry: () => void }) {
  return (
    <div class="notice error" role="alert">
      <span>Could not load {what}.</span>
      <button class="btn" type="button" onClick={onRetry}>
        Try again
      </button>
    </div>
  );
}
