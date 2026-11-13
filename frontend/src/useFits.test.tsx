import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as resume from "./api/resume";
import { settle } from "./components/loadFailed.helpers";
import { useFits } from "./useFits";

vi.mock("./api/resume", async (importOriginal) => ({ ...(await importOriginal<typeof resume>()), getFits: vi.fn() }));
const getFits = vi.mocked(resume.getFits);

const fit = (id: string, score: number) => ({ vacancyId: id, score }) as never;

let host: HTMLElement;
beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});
afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
  vi.restoreAllMocks();
});

function Probe({ ids }: { ids: string[] }) {
  const fits = useFits(ids);
  return <p>{Object.keys(fits).sort().map((id) => `${id}=${(fits[id] as unknown as { score: number }).score}`).join(" ")}</p>;
}
const show = async (ids: string[]) => {
  await act(async () => {
    render(<Probe ids={ids} />, host);
  });
  await settle(4);
};

describe("useFits", () => {
  it("asks only for rows it has not asked for and keeps the earlier answers", async () => {
    getFits.mockImplementation(async (ids) => ids.map((id) => fit(id, id === "a" ? 1 : 2)));

    await show(["a", "b"]);
    expect(getFits).toHaveBeenLastCalledWith(["a", "b"], expect.anything());
    expect(host.textContent).toBe("a=1 b=2");

    await show(["b"]); // a row left after a triage: no new request
    expect(getFits).toHaveBeenCalledTimes(1);

    await show(["b", "c"]);
    expect(getFits).toHaveBeenCalledTimes(2);
    expect(getFits).toHaveBeenLastCalledWith(["c"], expect.anything());
    expect(host.textContent).toBe("a=1 b=2 c=2");
  });

  it("asks again for rows whose request failed", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    getFits.mockRejectedValueOnce(new Error("offline")).mockResolvedValue([fit("a", 5)]);

    await show(["a"]);
    expect(host.textContent).toBe("");

    await show(["a", "b"]);
    expect(getFits).toHaveBeenCalledTimes(2);
    expect(getFits).toHaveBeenLastCalledWith(["a", "b"], expect.anything());
    expect(host.textContent).toBe("a=5");
  });

  it("makes no request for an empty list", async () => {
    await show([]);
    expect(getFits).not.toHaveBeenCalled();
  });
});
