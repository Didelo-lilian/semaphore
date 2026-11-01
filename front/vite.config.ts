import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// In development the page, the WebSocket and the API share an origin, as in production: the dev
// server (and `vite preview`, which reuses this) forwards /ws and /api to a backend started with
// SEMAPHORE_ALLOWED_ORIGINS=http://localhost:5173.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      "/ws": { target: "ws://localhost:8080", ws: true },
      "/api": { target: "http://localhost:8080" },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
  },
});
