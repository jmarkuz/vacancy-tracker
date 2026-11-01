import { defineConfig } from "vite";
import preact from "@preact/preset-vite";

// The production bundle is written into the Spring Boot static folder so the backend
// serves the UI from the same artifact (ADR-005).
export default defineConfig({
  plugins: [preact()],
  build: {
    outDir: "../backend/src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8080",
      "/actuator": "http://127.0.0.1:8080",
    },
  },
});
