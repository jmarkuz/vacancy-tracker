import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson } from "./client";
import { patchVacancy } from "./vacancies";

function respond(status: number, body: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("getJson", () => {
  it("returns the parsed body on success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(respond(200, { total: 3 })));
    await expect(getJson<{ total: number }>("/api/vacancies")).resolves.toEqual({ total: 3 });
  });

  it("uses the problem detail as the error message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(respond(409, { title: "Invalid status transition", detail: "Cannot move from New to Offer" })),
    );
    const error = await getJson("/api/vacancies/1").catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).message).toBe("Cannot move from New to Offer");
  });

  it("falls back to a generic message when the body is not JSON", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("<html>oops</html>", { status: 502 })));
    await expect(getJson("/api/x")).rejects.toThrow("Request failed (502)");
  });
});

describe("patchVacancy", () => {
  it("joins field errors from a 422 response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(respond(422, { title: "Validation failed", errors: { priority: "must be at most 3" } })),
    );
    await expect(patchVacancy("abc", { priority: 9 })).rejects.toThrow("must be at most 3");
  });

  it("sends a JSON body with PATCH", async () => {
    const fetchMock = vi.fn().mockResolvedValue(respond(200, { id: "abc" }));
    vi.stubGlobal("fetch", fetchMock);
    await patchVacancy("abc", { notes: "call back" });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/vacancies/abc");
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body as string)).toEqual({ notes: "call back" });
  });
});
