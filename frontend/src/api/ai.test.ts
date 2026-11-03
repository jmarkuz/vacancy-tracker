import { describe, expect, it } from "vitest";
import { coverLetterTemplate, latestByKind, type TailoredDocument } from "./ai";

const doc = (kind: TailoredDocument["kind"], version: number): TailoredDocument => ({
  id: `${kind}-${version}`,
  vacancyId: "v",
  kind,
  version,
  content: "x",
  origin: "manual",
  provider: null,
  model: null,
  snapshotId: null,
  pdfPages: null,
  factFindings: null,
  factsConfirmed: false,
  createdAt: "2026-11-02T10:00:00Z",
});

describe("documents", () => {
  it("picks the latest version of each kind", () => {
    const latest = latestByKind([doc("resume", 1), doc("resume", 3), doc("cover_letter", 2), doc("resume", 2)]);
    expect(latest.resume?.version).toBe(3);
    expect(latest.cover_letter?.version).toBe(2);
  });

  it("starts a letter with the company and the title", () => {
    expect(coverLetterTemplate("Engineer", "Acme")).toContain("Dear Acme team");
    expect(coverLetterTemplate("Engineer", "Acme")).toContain("the Engineer position");
  });
});
