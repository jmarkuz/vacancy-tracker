import { afterEach, describe, expect, it, vi } from "vitest";
import { logFailure } from "./logFailure";

afterEach(() => vi.restoreAllMocks());

describe("logFailure", () => {
  it("logs a failure with what could not be loaded", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    logFailure("the fit scores")(new Error("x"));
    expect(warn).toHaveBeenCalledWith("Could not load the fit scores", expect.any(Error));
  });

  it("stays quiet for an aborted request", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const controller = new AbortController();
    controller.abort();
    logFailure("x", controller.signal)(new Error("aborted"));
    expect(warn).not.toHaveBeenCalled();
  });
});
