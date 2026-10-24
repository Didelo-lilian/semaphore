# 4. A React front on the design system, with TanStack and Zod

Date: 2026-10-24 · Status: accepted

## Context

The server holds every room and every rule ([ADR 0003](0003-rooms-the-state-machine-and-the-protocol.md)); the browser only shows what it is told and sends what the player does. The front is served on the same host as the WebSocket (`play.didelo.fr`, `/ws` goes to the backend), so there is no cross-origin call.

## Decision

- **Vite, React 19, TypeScript, Biome, Vitest with Testing Library**, the toolchain of the design system. The screens use `@didelo/design-system` (Button, TextField, Alert, Chip, theme, fonts, tokens); what is specific to the game stays in the game ([ADR 0001](0001-name-rules-and-visual-direction.md)). The cards sit on the light color scheme of the theme (`data-theme="light"`), because the page is dark: a dark-theme field on a paper card is white on beige.
- **Zod validates every message** the server sends (`src/protocol.ts`), and gives the TypeScript type of the same schema. TypeScript alone cannot do it: its types are erased at build time and `JSON.parse` returns anything, while the backend is another repository deployed on its own. A message that breaks the contract is logged and the last good view stays on screen. Validating a real room message (3.4 KB) takes about 1 µs. The test fixtures are messages captured from the running backend.
- **TanStack Query** holds the room. It is server state that nobody fetches: the socket writes each view in the cache (`setQueryData`) and `useQuery` with `skipToken` reads it. Commands are mutations: `GameClient.request` gives the message an `id` and returns a promise settled by the answer that carries it (`joined` or an update resolves it, `error` rejects it, see the request ids of [ADR 0003](0003-rooms-the-state-machine-and-the-protocol.md)), so each form and each button has its own pending state and its own error, and an update caused by another player settles nothing. The mutations share a scope, so a command goes out once the previous one is answered; the ids would let two be in flight.
- **TanStack Router** gives the screens their URLs: `/` (with `?room=CODE`, the invitation link) and `/salon/$code`. A visitor of a room link with no seat in the tab is sent to the invitation. **TanStack Form** handles the home form.
- **The seat is kept per tab** (`sessionStorage`, token and code): a reload takes it back with `rejoin`, a lost connection is retried after 0.5, 1, 2, 4 then 8 seconds, and a second tab that takes the seat does not fight for it (a banner offers to take it back).
- **A view older than the one shown is dropped** (`version`): the server sends outside the room's lock, so two updates can arrive swapped. Seen for real while capturing the fixtures.
- **Served by nginx** (unprivileged, port 8080): the files of `/assets` are cached for good, any other path falls back to `index.html`. The security headers come from Traefik, not from here.

## Consequences

- The pull request checked it in two browser tabs: create, invitation link, teams, start, reload, a room that does not exist.
- The front and the backend are deployed separately, so while the request ids were introduced each had to cope with the other being one version behind: until a server had shown that it echoes ids, `GameClient` settled the oldest command with an answer that had none. That fallback is gone now that both are deployed (v0.2.0): a command is settled only by the answer that carries its id, so the front needs a backend that echoes ids.
- The bundle is 720 kB (220 kB gzipped), mostly MUI. Code splitting comes if it matters.
- This version shows the started game (grid, key for the Signaleurs only, flags); playing the turns is in [ADR 0005](0005-the-table-by-role-and-phase.md).
