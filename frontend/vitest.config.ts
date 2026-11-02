import { defineConfig } from "vitest/config";
import preact from "@preact/preset-vite";

// Node 22+ has its own experimental `localStorage` global, which shadows the jsdom one and is
// undefined without a backing file (Node 25+ turns it on by default). Switch it off so tests see
// jsdom's storage on every Node version; Node 20 does not know the flag, so it is not passed there.
// (`process` is read through globalThis because the shared tsconfig has no Node types.)
const nodeVersion = (globalThis as { process?: { versions: { node: string } } }).process?.versions.node ?? "0";
const nodeMajor = Number(nodeVersion.split(".")[0]);
const execArgv = nodeMajor >= 22 ? ["--no-experimental-webstorage"] : [];

export default defineConfig({
  plugins: [preact()],
  test: {
    environment: "jsdom",
    include: ["src/**/*.test.ts", "src/**/*.test.tsx"],
    pool: "forks",
    poolOptions: { forks: { execArgv } },
  },
});
