import { defineConfig, devices } from "@playwright/test";

const FRONT = "http://localhost:4173";

// The real backend (the jar) and the built front, served by `vite preview`, which forwards /ws to :8080 like the
// dev server does. Outside CI a backend that already listens is reused, so it can run in a container: it must allow
// the origin of the front (README). The preview is always started, so the build under test is the working tree's.
export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  reporter: process.env.CI ? "github" : "list",
  // The trace holds the four players' pages, with a screenshot and the DOM at each step. A button that is not there
  // fails after 10 seconds, not at the end of the minute of the test.
  use: { baseURL: FRONT, trace: "retain-on-failure", actionTimeout: 10_000 },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    {
      command: "java -jar ../backend/build/libs/semaphore.jar",
      url: "http://localhost:8080/actuator/health",
      env: { SEMAPHORE_ALLOWED_ORIGINS: FRONT },
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "npm run preview -- --port 4173 --strictPort",
      cwd: "../front",
      url: FRONT,
    },
  ],
});
