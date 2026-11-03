import { act } from "preact/test-utils";

/** Lets pending promises settle inside act(), as the panels load on mount. */
export async function settle(times = 3): Promise<void> {
  for (let i = 0; i < times; i++) {
    await act(async () => {
      await Promise.resolve();
    });
  }
}
