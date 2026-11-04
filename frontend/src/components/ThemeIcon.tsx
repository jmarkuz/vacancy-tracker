import type { Theme } from "../theme";

/**
 * The icon of a color theme: a sun for light, a moon for dark, a monitor for "follow the system". It draws with the
 * current text color, so it follows the button in both themes, and it is decorative: the button's own words name the
 * theme, so the icon is hidden from screen readers.
 */
export function ThemeIcon({ theme }: { theme: Theme }) {
  return (
    <svg
      class="theme-icon"
      data-theme-icon={theme}
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
      {theme === "light" && (
        <>
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
        </>
      )}
      {theme === "dark" && <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />}
      {theme === "system" && (
        <>
          <rect x="3" y="4" width="18" height="12" rx="2" />
          <path d="M8 20h8M12 16v4" />
        </>
      )}
    </svg>
  );
}
