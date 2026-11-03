import { describe, expect, it } from "vitest";
import { diffCounts, diffLines } from "./diff";

describe("diffLines", () => {
  it("keeps common lines and marks the added and removed ones in order", () => {
    const lines = diffLines("# Jane\n- Java\n- Old bullet\n## Skills", "# Jane\n- Kafka\n- Java\n## Skills");
    expect(lines).toEqual([
      { type: "same", text: "# Jane" },
      { type: "added", text: "- Kafka" },
      { type: "same", text: "- Java" },
      { type: "removed", text: "- Old bullet" },
      { type: "same", text: "## Skills" },
    ]);
    expect(diffCounts(lines)).toEqual({ added: 1, removed: 1 });
  });

  it("finds no change in identical texts, whatever the line endings", () => {
    expect(diffCounts(diffLines("a\r\nb", "a\nb"))).toEqual({ added: 0, removed: 0 });
  });
});
