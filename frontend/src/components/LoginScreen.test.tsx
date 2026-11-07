import { render } from "preact";
import { act } from "preact/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as auth from "../api/auth";
import * as client from "../api/client";
import { LoginScreen } from "./LoginScreen";

vi.mock("../api/auth", async (importOriginal) => ({ ...(await importOriginal<typeof auth>()), login: vi.fn() }));
const login = vi.mocked(auth.login);
let host: HTMLElement;

beforeEach(() => {
  host = document.createElement("div");
  document.body.appendChild(host);
});
afterEach(() => {
  render(null, host);
  host.remove();
  vi.resetAllMocks();
});

const mount = async (onLoggedIn = vi.fn()) => {
  await act(async () => {
    render(<LoginScreen onLoggedIn={onLoggedIn} />, host);
  });
  return onLoggedIn;
};

const fill = async (value: string) => {
  await act(async () => {
    const el = host.querySelector<HTMLInputElement>('input[type="password"]')!;
    el.value = value;
    el.dispatchEvent(new Event("input", { bubbles: true }));
  });
};

const submit = async () => {
  await act(async () => {
    host.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
  });
  await act(async () => {
    await Promise.resolve();
  });
};

describe("LoginScreen", () => {
  it("keeps Sign in off until a token is typed", async () => {
    await mount();
    expect(host.querySelector<HTMLButtonElement>("button")!.disabled).toBe(true);

    await fill("secret");
    expect(host.querySelector<HTMLButtonElement>("button")!.disabled).toBe(false);
  });

  it("signs in with the token and reports it", async () => {
    login.mockResolvedValue(undefined as never);
    const onLoggedIn = await mount();
    await fill("secret");

    await submit();

    expect(login).toHaveBeenCalledWith("secret");
    expect(onLoggedIn).toHaveBeenCalledTimes(1);
  });

  it("shows the server's reason when the token is refused", async () => {
    login.mockRejectedValue(new client.ApiError(401, "Wrong token"));
    const onLoggedIn = await mount();
    await fill("nope");

    await submit();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Wrong token");
    expect(onLoggedIn).not.toHaveBeenCalled();
    expect(host.querySelector<HTMLButtonElement>("button")!.disabled).toBe(false);
  });

  it("says the server could not be reached for any other failure", async () => {
    login.mockRejectedValue(new Error("offline"));
    await mount();
    await fill("x");

    await submit();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe("Could not reach the server.");
  });
});
