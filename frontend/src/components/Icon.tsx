import type { JSX } from "preact";

/**
 * The icons of the buttons: simple outlines drawn with the current text color, so they follow the button in every
 * theme and state. They are decorative: every button keeps its words (the accessible name is unchanged), so the icon
 * is hidden from screen readers. One set, one stroke width, so the buttons look like one family.
 */
export type IconName =
  | "table"
  | "board"
  | "columns"
  | "plus"
  | "filter"
  | "refresh"
  | "chevron-left"
  | "chevron-right"
  | "bookmark"
  | "save"
  | "trash"
  | "close"
  | "check"
  | "mail"
  | "upload"
  | "external"
  | "link"
  | "chevron-down"
  | "history";

const SHAPES: Record<IconName, JSX.Element> = {
  mail: (
    <>
      <rect x="3" y="5" width="18" height="14" rx="2" />
      <path d="M3.5 7l8.5 6 8.5-6" />
    </>
  ),
  upload: (
    <>
      <path d="M12 16V4M7 9l5-5 5 5" />
      <path d="M4 15v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4" />
    </>
  ),
  external: (
    <>
      <path d="M14 4h6v6M20 4l-9 9" />
      <path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5" />
    </>
  ),
  link: (
    <>
      <path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" />
      <path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" />
    </>
  ),
  "chevron-down": <path d="M6 9l6 6 6-6" />,
  history: (
    <>
      <path d="M3 12a9 9 0 1 0 3-6.7" />
      <path d="M3 4v5h5" />
      <path d="M12 7.5V12l3 2" />
    </>
  ),
  table: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M3 9.5h18M3 14.5h18" />
    </>
  ),
  board: (
    <>
      <rect x="3" y="4" width="5" height="16" rx="1.2" />
      <rect x="10" y="4" width="4" height="10" rx="1.2" />
      <rect x="16" y="4" width="5" height="13" rx="1.2" />
    </>
  ),
  columns: (
    <>
      <rect x="3" y="4" width="18" height="16" rx="2" />
      <path d="M9 4v16M15 4v16" />
    </>
  ),
  plus: <path d="M12 5v14M5 12h14" />,
  filter: <path d="M3 5h18l-7 8.5V19l-4 2v-7.5z" />,
  refresh: (
    <>
      <path d="M21 12a9 9 0 1 1-3-6.7" />
      <path d="M21 4v5h-5" />
    </>
  ),
  "chevron-left": <path d="M15 6l-6 6 6 6" />,
  "chevron-right": <path d="M9 6l6 6-6 6" />,
  bookmark: <path d="M6 3h12v18l-6-4.5L6 21z" />,
  save: (
    <>
      <path d="M5 3h11l3 3v15H5z" />
      <path d="M8 3v6h7V3M8 21v-7h8v7" />
    </>
  ),
  trash: (
    <>
      <path d="M4 7h16M10 11v6M14 11v6" />
      <path d="M6 7l1 13h10l1-13M9 7V4h6v3" />
    </>
  ),
  close: <path d="M6 6l12 12M18 6L6 18" />,
  check: <path d="M5 12.5l5 5 9-11" />,
};

export function Icon({ name, spin = false }: { name: IconName; spin?: boolean }) {
  return (
    <svg
      class={spin ? "icon icon-spin" : "icon"}
      data-icon={name}
      viewBox="0 0 24 24"
      width="1.1em"
      height="1.1em"
      fill="none"
      stroke="currentColor"
      stroke-width="2"
      stroke-linecap="round"
      stroke-linejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {SHAPES[name]}
    </svg>
  );
}
