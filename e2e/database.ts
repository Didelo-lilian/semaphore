// The Postgres the end-to-end test starts the backend on and reads back: a service container in CI, a container of your own
// elsewhere (README).
export const POSTGRES = {
  host: "localhost",
  port: 5432,
  user: "semaphore",
  password: "semaphore",
  database: "semaphore",
};
