import { describe, expect, it } from "vitest";
import { allowedNext } from "./statuses";

// These cases mirror VacancyStatusTest on the backend (ADR-023). Change both or neither.
describe("allowedNext (mirror of the backend transition table)", () => {
  it.each([
    ["New", "Saved"],
    ["New", "Expired"],
    ["Saved", "Applied"],
    ["Applied", "Screening"],
    ["Screening", "Test task"],
    ["Offer", "Accepted"],
  ])("allows %s -> %s", (from, to) => {
    expect(allowedNext(from)).toContain(to);
  });

  it.each([
    ["New", "Offer"],
    ["Applied", "New"],
    ["Accepted", "Applied"],
    ["Rejected", "Applied"],
  ])("forbids %s -> %s", (from, to) => {
    expect(allowedNext(from)).not.toContain(to);
  });

  it("lets terminal statuses only be restored to New", () => {
    expect(allowedNext("Hidden")).toEqual(["New"]);
    expect(allowedNext("Dismissed")).toEqual(["New"]);
  });

  it("returns no options for accepted and unknown statuses", () => {
    expect(allowedNext("Accepted")).toEqual([]);
    expect(allowedNext("Unknown")).toEqual([]);
  });
});
