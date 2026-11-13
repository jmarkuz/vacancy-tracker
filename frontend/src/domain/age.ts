/**
 * How old a posting is, in words, from the date the source gave. A missing date is "Not stated", never a guess:
 * most sources do not say, and the age of a vacancy must not be made up.
 */
export function postedText(postedAt: string | null | undefined, now: Date): string {
  const days = postedDays(postedAt, now);
  if (days === null) {
    return "Not stated";
  }
  if (days === 0) {
    return "Today";
  }
  if (days === 1) {
    return "1 day ago";
  }
  return `${days} days ago`;
}

/** Whole days since the posting date, never negative; null without a date or with one that is not a date. */
export function postedDays(postedAt: string | null | undefined, now: Date): number | null {
  if (!postedAt) {
    return null;
  }
  const posted = new Date(postedAt).getTime();
  if (Number.isNaN(posted)) {
    return null;
  }
  return Math.max(0, Math.floor((now.getTime() - posted) / 86_400_000));
}
