# 3. Rooms, the state machine and the protocol

Date: 2026-10-24 · Status: accepted

## Context

[ADR 0001](0001-name-rules-and-visual-direction.md) wrote the rules and [ADR 0002](0002-spring-boot-gradle-and-a-plain-websocket.md) chose the wire. This one says how a game lives on the server: where the rules are, which messages exist and what a player is allowed to see.

## Decision

### One place for the rules

`Room` (package `game`) is one game, from the lobby to the end, and the only class that knows the rules. It has no dependency on Spring or on the WebSocket: the clock and the random generator are given to it, so a test plays a whole game with a fixed seed. It is not thread safe; the WebSocket handler and the sweep synchronize on the room they touch.

### Four states

| State | Legal commands | Next state |
|---|---|---|
| `LOBBY` | `JoinTeam`, `Start` | `SIGNAL` |
| `SIGNAL` | `Signal` | `GUESS` |
| `GUESS` | `Reveal`, `Pass` | `SIGNAL` (turn over) or `ENDED` |
| `ENDED` | none | |

The state decides which commands are legal at all; the room then judges who sends it and what it says (the Signaleur of the team in play, a hidden card, a signal too close to the grid). Every refusal is an `IllegalMove` with a reason, answered to the sender only.

ADR 0001 announced five states with a "round end". A turn ends at once, so there is no state between two turns, and ADR 0001 is corrected.

```mermaid
stateDiagram-v2
  [*] --> LOBBY: createRoom
  LOBBY --> LOBBY: joinRoom, joinTeam
  LOBBY --> SIGNAL: start
  SIGNAL --> GUESS: signal
  GUESS --> GUESS: reveal a word of the team in play
  GUESS --> SIGNAL: pass, calm waters, the other team's word,<br/>a reef, the limit of n + 1 reached
  GUESS --> ENDED: a team has all its words,<br/>or a second reef
  ENDED --> [*]
```

### Roles

- The Signaleur is not picked: it is the first member of the team in the order they joined the room, and it stays for the whole grid. It cannot rotate between turns, because a former Signaleur who becomes a lookout still knows the key and the signal would no longer matter. Rotation comes with a rematch on a new grid.
- The host is the first player of the room. Only they start the game, once everyone has a team and each team has at least two players (`semaphore.min-team-size`). A room holds twelve players. Nobody joins once the game has started.
- The starting team is drawn at random, as are the grid and the key.

### What a player sees

Each player receives their own view of the room, never the room itself. The kind of a card (Bâbord, Tribord, calm waters, reef) is in the view only if the card is revealed, if the game is over, or if the viewer is a Signaleur: both Signaleurs see the key, the lookouts of both teams do not. The kind of a hidden card is therefore never on the wire to a lookout, and a test checks it over a real WebSocket. Every view carries the room's `version`, which grows with each change, so a client that receives two views out of order keeps the one with the highest version.

### The messages

One record per legal action, JSON with a `type` field. This is what a command does, here a signal:

```mermaid
sequenceDiagram
  participant S as Signaleur's browser
  participant H as GameSocketHandler
  participant R as Room
  participant L as Lookouts' browsers
  S->>H: {"type":"signal","id":"7","word":"voyage","number":2}
  H->>R: apply(player, Signal), under the room's lock
  alt refused
    R-->>H: IllegalMove(reason)
    H-->>S: {"type":"error","id":"7","reason":"..."}
  else accepted
    R-->>H: the room has changed
    Note over H: one view per player is built under the lock,<br/>the sends happen after it is released
    H-->>S: roomUpdated with id 7, the view with the key
    H-->>L: roomUpdated without id, the view without the key
  end
```

| Client sends | Meaning |
|---|---|
| `createRoom {name}` | open a room and join it |
| `joinRoom {code, name}` | join a room in its lobby |
| `rejoin {code, token}` | come back after a lost connection |
| `joinTeam {team}`, `start`, `signal {word, number}`, `reveal {index}`, `pass` | the commands above |

| Server sends | Meaning |
|---|---|
| `joined {id?, playerId, token, room}` | once, to the player who got in; the token is how they come back |
| `roomUpdated {id?, room}` | to every connected player of the room after each change, each with their view |
| `error {id?, reason}` | to the sender only; the connection stays open |

### Request ids

Every client message may carry an `id`, a string the client picks (the front counts from 1 on each page load, and the `rejoin` that takes a seat back carries one too). The server echoes it on the answer to that message and nowhere else: the `joined` that answers a `createRoom`, `joinRoom` or `rejoin`, the `roomUpdated` that goes to the sender of a command, or the `error` that refuses it. The views sent to the other players, and the one the joiner gets after their `joined`, carry none, and the key is absent from the JSON rather than `null`. A client can therefore tell the answer to its own command from an update caused by someone else, even when the two arrive in any order.

The id is the handler's business: `Room` never sees it, and `GameSocketHandler` threads it from the message to the views it builds. A message too broken to read has no id to echo, so its `error` carries none. The id is optional on the wire in both directions, so the contract changed without a flag day: a front from before it sends no id and gets none back, and a server from before it ignores the id a newer front sends (the front then settles its commands in order, see [ADR 0004](0004-a-react-front-on-the-design-system.md)).

### Rooms

`RoomRegistry` keeps every room in memory under a five character code without `0`, `O`, `1`, `I` or `L`, since it is read aloud and typed. A room nobody is connected to for 30 minutes is dropped by a sweep that runs every minute, and so is a room nobody ever joined. A connection holds one seat: a second `createRoom`, `joinRoom` or `rejoin` on it is refused, and a room that could not be entered (a bad name) is dropped at once. A message with a missing field is as unreadable as broken JSON.

A player is the connection that holds their token. In the lobby, closing the connection removes the player, so nobody absent blocks the start or keeps the host seat. Once the game has started, closing it only marks them disconnected and `rejoin` with the token gives the seat back; the new connection replaces the old one, which is closed, so a late close of the old one changes nothing.

Changes happen under the room's lock but the messages go out after it is released: a slow or broken connection (its buffer overflowing throws `SessionLimitExceededException`) never holds a room or stops the others from getting their view.

### The words

`words.txt` holds 160 common French nouns written for the game. A test keeps the list clean: no duplicate and no two words that contain each other or share their first four letters, since two such words on one grid would forbid each other as signals.

## Consequences

- Messages are sent outside the room's lock, so two updates can reach a client out of order: the front keeps the view with the highest `version`.
- A Signaleur who disconnects blocks the turn until they come back. This is a known limit of v0.1; a timeout that passes the turn is the next step if it happens in practice.
- A restart of the server loses every room. With several replicas a room lives in one of them: sticky sessions and Redis pub/sub come with the scaling step ([ADR 0002](0002-spring-boot-gradle-and-a-plain-websocket.md)), and `Room` does not change for it.
- The records of `ClientMessage`, `ServerMessage` and `RoomView` are the contract the front validates with Zod ([#4](https://github.com/Didelo-lilian/semaphore/issues/4)).
- A command that the server never answers is not timed out: only a lost connection rejects it.
- Twelve players, two per team at least and 160 words are constants or configuration, not rules: they can move without touching the state machine.
