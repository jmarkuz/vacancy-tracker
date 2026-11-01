import { describe, expect, it } from "vitest";
import { parseRoute } from "./router";

describe("parseRoute", () => {
  it("reads the screen and query from the hash", () => {
    const route = parseRoute("#/pipeline?sort=title:asc&page=2");
    expect(route.screen).toBe("pipeline");
    expect(route.params.get("sort")).toBe("title:asc");
    expect(route.params.get("page")).toBe("2");
  });

  it("falls back to the dashboard for unknown or empty hashes", () => {
    expect(parseRoute("").screen).toBe("dashboard");
    expect(parseRoute("#/nope").screen).toBe("dashboard");
  });

  it("decodes encoded JSON filters", () => {
    const filter = JSON.stringify([{ field: "title", operator: "contains_any", value: ["a&b"] }]);
    const route = parseRoute(`#/pipeline?filter=${encodeURIComponent(filter)}`);
    expect(route.params.get("filter")).toBe(filter);
  });
});
