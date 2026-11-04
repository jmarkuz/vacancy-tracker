import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as authApi from "./api/auth";
import { settle } from "./components/loadFailed.helpers";

vi.mock("./api/auth", async (importOriginal) => ({ ...(await importOriginal<typeof authApi>()), getAuthStatus: vi.fn() }));
vi.mock("./screens/Dashboard", () => ({ Dashboard: () => <h1>Dashboard stub</h1> }));
vi.mock("./components/RefreshControl", () => ({ DATA_CHANGED_EVENT: "vt-data-changed", RefreshControl: () => null }));
vi.mock("./api/profiles", () => ({ listProfiles: vi.fn(async () => [{ id: "p" }]) }));

import { App } from "./App";

const getAuthStatus = vi.mocked(authApi.getAuthStatus);
const realLocation = window.location;
let host: HTMLElement;

const setHost = (hostname: string) => {
  Object.defineProperty(window, "location", { configurable: true, value: { ...realLocation, hostname, hash: "#/dashboard" } });
};

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});
afterEach(() => {
  render(null, host);
  host.remove();
  Object.defineProperty(window, "location", { configurable: true, value: realLocation });
  vi.resetAllMocks();
});

const show = async () => {
  await act(async () => {
    render(<App />, host);
  });
  await settle(4);
};

describe("App auth status failure", () => {
  it("shows a connection error with Retry away from the local profile", async () => {
    setHost("tracker.example.com");
    getAuthStatus.mockRejectedValueOnce(new Error("down"));
    await show();
    expect(host.querySelector("[role=alert]")?.textContent).toContain("Could not reach the server");
    expect(host.querySelector("#content")).toBeNull();

    getAuthStatus.mockResolvedValueOnce({ required: true, authenticated: true });
    await act(async () => {
      (host.querySelector("button.btn") as HTMLButtonElement).click();
    });
    await settle(4);
    expect(host.querySelector("#content")).not.toBeNull();
    expect(getAuthStatus).toHaveBeenCalledTimes(2);
  });

  it("falls back to open on the local address", async () => {
    setHost("localhost");
    getAuthStatus.mockRejectedValueOnce(new Error("down"));
    await show();
    expect(host.querySelector("#content")).not.toBeNull();
  });
});
