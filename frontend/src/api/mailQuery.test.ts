import { afterEach, describe, expect, it, vi } from "vitest";
import { getMailCounts, listMail } from "./mail";

function stubFetch() {
  const fetchMock = vi.fn(async (_url: string) => ({ ok: true, status: 200, json: async () => [], text: async () => "" }));
  vi.stubGlobal("fetch", fetchMock);
  return () => fetchMock.mock.calls[0][0];
}

afterEach(() => vi.unstubAllGlobals());

describe("listMail and getMailCounts build the query", () => {
  it("sends no query for no options (the drawer's panel keeps working)", async () => {
    const url = stubFetch();
    await listMail();
    expect(url()).toBe("/api/mail");
  });

  it("keeps vacancyId and pending and adds category, linked and a trimmed q", async () => {
    const url = stubFetch();
    await listMail({ vacancyId: "v1", pending: true, categories: ["offer", "job_alert"], linked: false, q: "  100% _ " });
    const u = new URL(url(), "http://x");
    expect(u.pathname).toBe("/api/mail");
    expect(u.searchParams.get("vacancyId")).toBe("v1");
    expect(u.searchParams.get("pending")).toBe("true");
    expect(u.searchParams.get("category")).toBe("offer,job_alert");
    expect(u.searchParams.get("linked")).toBe("false");
    expect(u.searchParams.get("q")).toBe("100% _");
  });

  it("leaves the categories out of the counts", async () => {
    const url = stubFetch();
    await getMailCounts({ categories: ["offer"], linked: true, q: "acme" });
    const u = new URL(url(), "http://x");
    expect(u.pathname).toBe("/api/mail/counts");
    expect(u.searchParams.has("category")).toBe(false);
    expect(u.searchParams.get("linked")).toBe("true");
    expect(u.searchParams.get("q")).toBe("acme");
  });
});
