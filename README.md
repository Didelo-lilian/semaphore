# semaphore
Real-time multiplayer word game for two teams: one player signals a word and a number, the others find the right words on the grid. Spring Boot, React, WebSockets.

**Sémaphore** is a word game for two teams on a 6×4 grid. Each team has a Signaleur who sends a word and a number, and lookouts who must find the matching words without hitting a reef. The rules and the visual direction are in [ADR 0001](docs/adr/0001-name-rules-and-visual-direction.md).

You can create a room, invite friends with a link, pick a team, start the game and play it to the end: the Signaleur sends a signal, the lookouts touch words or pass, and the winner is announced with the whole key. The backend enforces every rule over a WebSocket; the front shows what it is told ([ADR 0004](docs/adr/0004-a-react-front-on-the-design-system.md)) and lets each player do only what their role allows in the phase of the game ([ADR 0005](docs/adr/0005-the-table-by-role-and-phase.md)).

## Run the backend

```bash
cd backend
./gradlew bootRun   # needs a JDK 25; http://localhost:8080/actuator/health
./gradlew check     # formatting and tests
```

The WebSocket is `/ws`; for local development set `SEMAPHORE_ALLOWED_ORIGINS=http://localhost:5173`. The choices behind it are in [ADR 0002](docs/adr/0002-spring-boot-gradle-and-a-plain-websocket.md). The rooms, the states and the messages are in [ADR 0003](docs/adr/0003-rooms-the-state-machine-and-the-protocol.md).

## Run the front

```bash
cd front
npm ci
npm run dev     # http://localhost:5173, /ws goes to a backend on :8080
npm run check   # lint, types, tests, production build
```

Start the backend with `SEMAPHORE_ALLOWED_ORIGINS=http://localhost:5173` (a port taken by another server makes Vite refuse to start with `--strictPort`, and the origin must match the port actually used).

## Architecture

The diagram is updated in the same pull request as any change it shows.

```mermaid
flowchart LR
  player([Player's browser])

  subgraph vps["Didelo VPS · play.didelo.fr"]
    traefik["Traefik<br/>TLS, CrowdSec, headers, rate limit"]
    front["play · nginx<br/>React front (static files)"]
    subgraph app["Spring Boot 4 · JDK 25 · one container"]
      handler["GameSocketHandler<br/>/ws, one connection per player,<br/>refuses other origins"]
      registry["RoomRegistry<br/>rooms in memory, 5-character codes,<br/>sweep every minute"]
      room["Room<br/>the only place that knows the rules"]
      words[("words.txt<br/>160 French nouns")]
    end
  end

  player -->|"HTTPS, WSS"| traefik
  traefik -->|"/"| front
  traefik -->|"/ws"| handler
  handler -->|"create, find"| registry
  registry -->|"builds a grid from"| words
  handler -->|"apply(player, command)<br/>under the room's lock"| room
  room -->|"RoomView, one per player:<br/>the key only for the Signaleurs"| handler
```

## The table

What a player sees and can do depends on the phase of the room, on the team in play and on their role. The screen is a function of the view the server sends to that player, so coming back after a reload needs nothing more. [ADR 0005](docs/adr/0005-the-table-by-role-and-phase.md) has the diagram of who may act in each phase; this one follows a turn.

```mermaid
sequenceDiagram
  participant S as Signaleur (team in play)
  participant G as Server
  participant L as Lookouts (team in play)
  participant O as Everybody else
  Note over S,O: SIGNAL, only the Signaleur has a form
  S->>G: signal (word, number)
  G-->>S: view, "Tes Veilleurs cherchent…"
  G-->>L: view, pennant, words to touch, Passer
  G-->>O: view, pennant, "Les Veilleurs de … cherchent."
  Note over S,O: GUESS, only the lookouts act
  loop while the words are their team's, n + 1 at most
    L->>G: reveal (index)
    G-->>S: view, the card turns over
    G-->>L: view, the card turns over
    G-->>O: view, the card turns over
  end
  L->>G: pass (or a wrong word, a reef, the limit)
  G-->>S: view, SIGNAL, the other team's turn
  G-->>L: view, SIGNAL, the other team's turn
  G-->>O: view, SIGNAL, the other team's turn
```

## Tests

| Kind | Where | What it proves |
|---|---|---|
| Rules | `RoomTest` | every legal and illegal move in each state, signal validation, both ways to win, the Signaleur fixed for the grid, leaving the lobby, the key never in a lookout's view |
| Registry, words | `RoomRegistryTest`, `WordListTest` | codes, sweep of abandoned rooms, a clean word list with no two words too close |
| WebSocket | `WebSocketIT` | the application on a random port, driven by the JDK's WebSocket client: create, join, leaving the lobby, rejoin with a token, a new connection replacing the old, errors and missing fields, the id of a message echoed on its answer and absent from what goes to the others, a started game, refused origin |
| Handler | `GameSocketHandlerTest` | a broken connection does not stop the others, a room that could not be entered is not kept, one room per connection |
| Protocol | `protocol.test.ts` | the Zod schemas read messages captured from the real backend (lobby join and answered update with their id, Signaleur's view, lookout's view without one, error with its id) and refuse anything else |
| Client | `client.test.ts` | the connection with a fake WebSocket: queued messages, seat remembered, late views dropped, rejoin after a reload, retries with growing delays, no fight between two tabs, each command settled by the answer that carries its id (two in flight, an update caused by someone else settles nothing), the oldest command settled for a server that echoes no id |
| Screens | `app.test.tsx`, `screens.test.tsx` | create a room and land in its lobby, invitation link, refusal shown where it was asked, room link without a seat, seat back after a reload, team choice, start rules, the key only for Signaleurs, the table for each role and phase (signal form, words to touch, waiting lines, winner and whole key), a whole game played as a lookout through the router and a fake socket |

`./gradlew check` runs the backend ones with the formatting check, `npm run check` the front ones with lint, types and the build. The pull request runs both (JDK 25, Node 24), builds the two images, lints the workflows, scans for secrets and runs CodeQL.

## Delivery

```mermaid
flowchart LR
  dev["Branch<br/>signed commits"] --> pr["Pull request<br/>backend and front checks, image builds,<br/>actionlint, gitleaks, CodeQL, title"]
  pr -->|"squash merge"| main["main"]
  main --> image["Image workflow"]
  image --> ghcr[("GHCR<br/>semaphore:main<br/>semaphore-front:main")]
  image -->|"GitHub App token"| infra["infra · Deploy app<br/>stacks semaphore and play"]
  infra -->|"pull :main, restart"| vps["VPS"]
  main --> release["Release workflow<br/>release-please"]
  release --> relpr["Release pull request<br/>version + CHANGELOG"]
  relpr -->|"merge"| tag["Tag vX.Y.Z<br/>GitHub release"]
```

The release workflow needs the `didelo-release` GitHub App installed on this repository, with its `RELEASE_APP_ID` variable and `RELEASE_APP_PRIVATE_KEY` secret ([#5](https://github.com/Didelo-lilian/semaphore/issues/5)).

