import { describe, expect, it } from "vitest";
import {
  MAX_WIDTH,
  MIN_WIDTH,
  STORAGE_KEY,
  clampWidth,
  loadWidths,
  parseWidths,
  saveWidths,
  widthOf,
  withWidth,
  withoutWidth,
} from "./columnWidths";

describe("column widths", () => {
  it("keeps a width between the smallest and the largest", () => {
    expect(clampWidth(10)).toBe(MIN_WIDTH);
    expect(clampWidth(5000)).toBe(MAX_WIDTH);
    expect(clampWidth(200.4)).toBe(200);
  });

  it("gives the user's width first, then the default, and none for the title", () => {
    expect(widthOf("company", {})).toBe(170);
    expect(widthOf("company", { company: 300 })).toBe(300);
    expect(widthOf("title", {})).toBeUndefined();
    expect(widthOf("title", { title: 400 })).toBe(400);
  });

  it("sets and clears one column without touching the others", () => {
    const set = withWidth({ status: 100 }, "company", 9999);
    expect(set).toEqual({ status: 100, company: MAX_WIDTH });
    expect(withoutWidth(set, "company")).toEqual({ status: 100 });
  });

  it("reads stored widths and drops what is not a number", () => {
    expect(parseWidths(null)).toEqual({});
    expect(parseWidths("not json")).toEqual({});
    expect(parseWidths("[1,2]")).toEqual({});
    expect(parseWidths("7")).toEqual({});
    expect(parseWidths('{"company":250,"status":"wide","tags":1}')).toEqual({ company: 250, tags: MIN_WIDTH });
  });

  it("saves and loads through a storage, and survives a storage that throws", () => {
    const data: Record<string, string> = {};
    const storage = { getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => void (data[k] = v) };
    saveWidths({ company: 240 }, storage);
    expect(data[STORAGE_KEY]).toBe('{"company":240}');
    expect(loadWidths(storage)).toEqual({ company: 240 });

    const broken = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("full");
      },
    };
    expect(loadWidths(broken)).toEqual({});
    expect(() => saveWidths({ company: 240 }, broken)).not.toThrow();
    expect(loadWidths(undefined)).toEqual({});
  });
});
