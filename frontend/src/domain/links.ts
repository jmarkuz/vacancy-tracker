/**
 * A link from vacancy data, or null when it is not an http(s) address. Links come from many sources and from
 * imports, and go into href and window.open: a javascript: or data: value must never get there (review 2026-11-02).
 */
export function safeLink(url: string | null | undefined): string | null {
  if (!url) {
    return null;
  }
  try {
    const parsed = new URL(url.trim());
    return parsed.protocol === "http:" || parsed.protocol === "https:" ? parsed.href : null;
  } catch {
    return null;
  }
}
