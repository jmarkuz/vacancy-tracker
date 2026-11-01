/** Status as a dot and a text label. Color is never the only signal (design README). */
export function StatusBadge({ status }: { status: string }) {
  const slug = status.toLowerCase().replace(/\s+/g, "-");
  return <span class={`badge status-${slug}`}>{status}</span>;
}
