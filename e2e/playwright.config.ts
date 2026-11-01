import { defineConfig, devices } from "@playwright/test";
import { POSTGRES } from "./database";

const FRONT = "http://localhost:4173";
const PROVIDER = "http://localhost:9100";

// The real backend (the jar) with a Postgres and a fake GitHub for the sign-in, and the built front, served by `vite
// preview`, which forwards /ws and /api to :8080 like the dev server does. Outside CI a backend that already listens is
// reused, so it can run in a container: it must allow the origin of the front and be set up as below (README). The
// preview is always started, so the build under test is the working tree's.
export default defineConfig({
  testDir: ".",
  timeout: 60_000,
  reporter: process.env.CI ? "github" : "list",
  // The trace holds the four players' pages, with a screenshot and the DOM at each step. A button that is not there
  // fails after 10 seconds, not at the end of the minute of the test.
  use: { baseURL: FRONT, trace: "retain-on-failure", actionTimeout: 10_000 },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: [
    { command: "node fake-provider.ts", url: `${PROVIDER}/health` },
    {
      command: "java -jar ../backend/build/libs/semaphore.jar",
      url: "http://localhost:8080/actuator/health",
      env: {
        SEMAPHORE_ALLOWED_ORIGINS: FRONT,
        SPRING_DATASOURCE_URL: `jdbc:postgresql://${POSTGRES.host}:${POSTGRES.port}/${POSTGRES.database}`,
        SPRING_DATASOURCE_USERNAME: POSTGRES.user,
        SPRING_DATASOURCE_PASSWORD: POSTGRES.password,
        SEMAPHORE_AUTH_GITHUB_CLIENT_ID: "e2e",
        SEMAPHORE_AUTH_GITHUB_CLIENT_SECRET: "e2e",
        SEMAPHORE_AUTH_GITHUB_AUTHORIZATION_URI: `${PROVIDER}/authorize`,
        SEMAPHORE_AUTH_GITHUB_TOKEN_URI: `${PROVIDER}/token`,
        SEMAPHORE_AUTH_GITHUB_USER_INFO_URI: `${PROVIDER}/user`,
      },
      reuseExistingServer: !process.env.CI,
    },
    {
      command: "npm run preview -- --port 4173 --strictPort",
      cwd: "../front",
      url: FRONT,
    },
  ],
});
