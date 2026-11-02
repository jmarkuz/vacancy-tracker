import type { HealthState, SourceStatus } from "../api/sources";

/** Text label for a health state. Shown next to the colored dot, never color alone. */
export function healthLabel(health: HealthState): string {
  switch (health) {
    case "ok":
      return "OK";
    case "warn":
      return "Warning";
    case "error":
      return "Error";
    default:
      return "Not run yet";
  }
}

/** One line of explanation: why the source has its state, in plain words. */
export function healthDetail(source: SourceStatus, locale?: string): string {
  if (source.health === "unknown") {
    return "No run has reached this source yet.";
  }
  if (source.health === "error" && source.circuitOpenUntil) {
    const until = new Intl.DateTimeFormat(locale, { hour: "2-digit", minute: "2-digit" }).format(
      new Date(source.circuitOpenUntil),
    );
    return `Paused after ${source.consecutiveFailures} failures in a row; retry after ${until}.`;
  }
  if (source.consecutiveFailures > 0) {
    return `${source.consecutiveFailures} failure${source.consecutiveFailures === 1 ? "" : "s"} in a row.`;
  }
  return "Last run succeeded.";
}
