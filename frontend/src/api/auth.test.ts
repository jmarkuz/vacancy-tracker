import { afterEach, describe, expect, it, vi } from "vitest";
import { isLocalHost, login, retryText, UNAUTHORIZED_EVENT, watchUnauthorized } from "./auth";

afterEach(() => vi.unstubAllGlobals());

describe("retryText", () => {
  it("rounds up to whole minutes", () => {
    expect(retryText("540")).toBe("Too many wrong tokens. Try again in 9 minutes.");
    expect(retryText("61")).toBe("Too many wrong tokens. Try again in 2 minutes.");
    expect(retryText("30")).toBe("Too many wrong tokens. Try again in 1 minute.");
  });

  it("falls back to a general sentence for a missing or odd value", () => {
    expect(retryText(null)).toBe("Too many wrong tokens. Try again later.");
    expect(retryText("soon")).toBe("Too many wrong tokens. Try again later.");
    expect(retryText("0")).toBe("Too many wrong tokens. Try again later.");
  });
});

describe("login", () => {
  const answer = (status: number, headers: Record<string, string> = {}) =>
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status, headers })));

  it("resolves when the token is accepted", async () => {
    answer(204);
    await expect(login("t")).resolves.toBeUndefined();
  });

  it("explains a wrong token and a rate limit", async () => {
    answer(401);
    await expect(login("t")).rejects.toMatchObject({ status: 401, message: "That token is not correct." });
    answer(429, { "Retry-After": "120" });
    await expect(login("t")).rejects.toMatchObject({ status: 429, message: "Too many wrong tokens. Try again in 2 minutes." });
  });
});

describe("watchUnauthorized", () => {
  function fakeWindow(status: number) {
    const target = new EventTarget() as unknown as Window;
    target.fetch = vi.fn().mockResolvedValue(new Response(null, { status }));
    const seen = vi.fn();
    target.addEventListener(UNAUTHORIZED_EVENT, seen);
    watchUnauthorized(target);
    return { target, seen };
  }

  it("announces a 401 from the data API", async () => {
    const { target, seen } = fakeWindow(401);
    await target.fetch("/api/vacancies");
    expect(seen).toHaveBeenCalledTimes(1);
  });

  it("ignores 401 from the auth endpoints, other statuses, and other addresses", async () => {
    const wrong = fakeWindow(401);
    await wrong.target.fetch("/api/auth/login");
    await wrong.target.fetch("/somewhere/else");
    expect(wrong.seen).not.toHaveBeenCalled();

    const fine = fakeWindow(200);
    await fine.target.fetch("/api/vacancies");
    expect(fine.seen).not.toHaveBeenCalled();
  });

  it("returns the response untouched", async () => {
    const { target } = fakeWindow(401);
    expect((await target.fetch("/api/vacancies")).status).toBe(401);
  });
});

describe("isLocalHost", () => {
  it("accepts only loopback names", () => {
    expect(isLocalHost("localhost")).toBe(true);
    expect(isLocalHost("127.0.0.1")).toBe(true);
    expect(isLocalHost("[::1]")).toBe(true);
    expect(isLocalHost("tracker.example.com")).toBe(false);
  });
});
