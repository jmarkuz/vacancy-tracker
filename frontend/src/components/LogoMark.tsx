/**
 * The Beacon mark (design/vacancy-tracker-beacon-handoff-v1/beacon-handoff/assets/logo-primary.svg): a geometric
 * beacon in the shape of an A with a crossbar. It takes the logo color (ink in Graphite, ADR-053: never the color of a section).
 */
export function LogoMark({ title }: { title?: string }) {
  return (
    <svg class="logo-mark" viewBox="0 0 48 48" role={title ? "img" : undefined} aria-label={title} aria-hidden={title ? undefined : true}>
      <g fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round">
        <path d="M12 31 24 8l12 23M17 23h14" />
      </g>
    </svg>
  );
}
