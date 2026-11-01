import { createServer } from "node:http";

// A GitHub that signs everybody in as the same person, for the sign-in of the end-to-end test (ADR 0011): the
// backend is pointed at it instead of github.com. It checks nothing (no state, no PKCE, no secret): the backend's own
// tests do, against a stricter fake.
const PORT = 9100;
const SUBJECT = 4242;

const json = (body: unknown) => ({
  status: 200,
  type: "application/json",
  body: JSON.stringify(body),
});

createServer((request, response) => {
  const url = new URL(request.url ?? "/", `http://localhost:${PORT}`);
  if (url.pathname === "/authorize") {
    const redirect = url.searchParams.get("redirect_uri");
    if (redirect === null) {
      response.writeHead(400).end();
      return;
    }
    const back = new URL(redirect);
    back.searchParams.set("code", "e2e");
    back.searchParams.set("state", url.searchParams.get("state") ?? "");
    response.writeHead(302, { Location: back.toString() }).end();
    return;
  }
  const answer =
    url.pathname === "/token"
      ? json({ access_token: "e2e", token_type: "bearer" })
      : url.pathname === "/user"
        ? json({ id: SUBJECT, login: "e2e" })
        : url.pathname === "/health"
          ? { status: 200, type: "text/plain", body: "ok" }
          : { status: 404, type: "text/plain", body: "" };
  response
    .writeHead(answer.status, { "Content-Type": answer.type })
    .end(answer.body);
}).listen(PORT);
