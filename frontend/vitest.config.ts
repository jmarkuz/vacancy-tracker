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
    // The contrast test reads tokens.css as text (?raw); other styles stay out of the tests.
    css: { include: [/tokens\.css/] },
    poolOptions: { forks: { execArgv } },
    // `npm run test:coverage` (and CI) fail when coverage drops. The floors sit just under today's numbers and
    // only go up: the pure rules in src/domain are tested almost completely, the screens and components are
    // covered by the Playwright tests in e2e/ and by the few component tests here. Raise a floor with the PR that
    // adds the tests, never lower one.
    coverage: {
      provider: "v8",
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.{ts,tsx}", "src/main.tsx", "src/**/*.d.ts"],
      reporter: ["text-summary", "json-summary"],
      thresholds: {
        lines: 17,
        functions: 50,
        branches: 85,
        "src/domain/**": { lines: 95, functions: 95, branches: 90 },
      },
    },
  },
});
