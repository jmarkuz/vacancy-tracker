/**
 * Keyboard of a tab list (Beacon, WAI-ARIA tabs): the arrows move to the next or previous tab and wrap, Home and End
 * go to the ends; any other key leaves the tab as it is (null).
 */
export function nextTab(key: string, index: number, count: number): number | null {
  switch (key) {
    case "ArrowRight":
    case "ArrowDown":
      return (index + 1) % count;
    case "ArrowLeft":
    case "ArrowUp":
      return (index - 1 + count) % count;
    case "Home":
      return 0;
    case "End":
      return count - 1;
    default:
      return null;
  }
}
