/** One line of a line-by-line comparison: unchanged, only in the tailored text, or only in the base draft. */
export interface DiffLine {
  type: "same" | "added" | "removed";
  text: string;
}

const MAX_CELLS = 4_000_000;

/**
 * The lines of base and tailored in order, marked as kept, added or removed (longest common subsequence). Very long
 * texts that would need more than a few million comparisons fall back to "all removed, all added".
 */
export function diffLines(base: string, tailored: string): DiffLine[] {
  const a = base.replace(/\r\n/g, "\n").split("\n");
  const b = tailored.replace(/\r\n/g, "\n").split("\n");
  if (a.length * b.length > MAX_CELLS) {
    return [...a.map((text) => ({ type: "removed" as const, text })), ...b.map((text) => ({ type: "added" as const, text }))];
  }
  // lcs[i][j]: length of the common subsequence of a[i..] and b[j..].
  const lcs: Uint32Array[] = Array.from({ length: a.length + 1 }, () => new Uint32Array(b.length + 1));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ type: "same", text: a[i] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ type: "removed", text: a[i++] });
    } else {
      out.push({ type: "added", text: b[j++] });
    }
  }
  while (i < a.length) {
    out.push({ type: "removed", text: a[i++] });
  }
  while (j < b.length) {
    out.push({ type: "added", text: b[j++] });
  }
  return out;
}

/** How many lines were added and removed. */
export function diffCounts(lines: DiffLine[]): { added: number; removed: number } {
  return {
    added: lines.filter((l) => l.type === "added").length,
    removed: lines.filter((l) => l.type === "removed").length,
  };
}
