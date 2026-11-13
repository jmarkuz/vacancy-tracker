import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { settle } from "./components/loadFailed.helpers";
import { lazyComponent } from "./lazyComponent";

let host: HTMLElement;
beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});
afterEach(() => {
  render(null, host);
  host.remove();
});

const Hello = (props: { name: string }) => <p class="hello">Hello {props.name}</p>;
const show = async (Lazy: ReturnType<typeof lazyComponent<{ name: string }>>) => {
  await act(async () => {
    render(<Lazy name="Ann" />, host);
  });
  await settle(4);
};

describe("lazyComponent", () => {
  it("shows a status line while the code arrives, then the screen with its props", async () => {
    let release!: () => void;
    const load = vi.fn(() => new Promise<typeof Hello>((resolve) => (release = () => resolve(Hello))));
    const Lazy = lazyComponent(load, "the greeting");

    await act(async () => {
      render(<Lazy name="Ann" />, host);
    });
    expect(host.querySelector('[role="status"]')?.textContent).toBe("Loading the greeting…");

    release();
    await settle(4);
    expect(host.querySelector(".hello")?.textContent).toBe("Hello Ann");
  });

  it("loads the code once and shows a later visit at once", async () => {
    const load = vi.fn(async () => Hello);
    const Lazy = lazyComponent(load, "the greeting");

    await show(Lazy);
    render(null, host);
    await show(Lazy);

    expect(load).toHaveBeenCalledTimes(1);
    expect(host.querySelector(".hello")).not.toBeNull();
    expect(host.querySelector('[role="status"]')).toBeNull();
  });

  it("offers Try again when the code cannot be fetched, and loads it on the retry", async () => {
    const load = vi.fn<() => Promise<typeof Hello>>().mockRejectedValueOnce(new Error("chunk gone")).mockResolvedValue(Hello);
    const Lazy = lazyComponent(load, "the greeting");

    await show(Lazy);
    expect(host.querySelector('[role="alert"]')?.textContent).toContain("Could not load the greeting.");

    await act(async () => {
      host.querySelector<HTMLButtonElement>("button")!.click();
    });
    await settle(4);

    expect(load).toHaveBeenCalledTimes(2);
    expect(host.querySelector(".hello")?.textContent).toBe("Hello Ann");
  });
});
