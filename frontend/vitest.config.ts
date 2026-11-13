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
    // The style files are read as text (?raw) by the contrast, glass and syntax tests; Vitest hands any other CSS
    // over as an empty string, which would leave those tests with nothing to check. Components do not import CSS.
    css: { include: [/src\/styles\/.*\.css/] },
    execArgv,
    // `npm run test:coverage` (and CI) fail when coverage drops. The floors sit just under today's numbers and
    // only go up: the pure rules in src/domain are tested almost completely, the screens and components are
    // covered by the Playwright tests in e2e/ and by the few component tests here. Raise a floor with the PR that
    // adds the tests, never lower one. The overall branch floor is lower than today's value on purpose: V8 counts
    // the branches only of files a test loads, so testing a large component adds its branches to the figure and can
    // lower the percentage while coverage really grows. Lines and functions count every file in src.
    coverage: {
      provider: "v8",
      // No `include` here: since Vitest 5 a set `include` also adds every untested file as zero, which would move
      // the figures without any test change. Without it only the files the tests load are counted, as before.
      exclude: ["src/**/*.test.{ts,tsx}", "src/main.tsx", "src/**/*.d.ts"],
      reporter: ["text-summary", "json-summary"],
      thresholds: {
        lines: 17,
        functions: 50,
        branches: 75,
        "src/domain/**": { lines: 95, functions: 95, branches: 90 },
      },
    },
  },
});
