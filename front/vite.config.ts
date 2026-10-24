import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// In development the page and the WebSocket share an origin, as in production: the dev server
// forwards /ws to a backend started with SEMAPHORE_ALLOWED_ORIGINS=http://localhost:5173.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { "/ws": { target: "ws://localhost:8080", ws: true } },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.ts"],
  },
});
