# 2. Spring Boot, Gradle and a plain WebSocket

Date: 2026-10-24 · Status: accepted

## Context

The backend holds every room and enforces every rule ([ADR 0001](0001-name-rules-and-visual-direction.md)). It talks to browsers over WebSockets, runs on a small server next to the other projects, and must be easy to run twice behind a load balancer later.

## Decision

- **Spring Boot 4.1** (the latest stable release; 4.2 is still a release candidate) on the **JDK 25** LTS. The build and the tests run on that JDK in CI and in the image.
- **Gradle** with the Kotlin DSL and a wrapper, so a clone builds with one command and the version is in git. **Spotless** (google-java-format) checks the formatting in `check`.
- **A plain WebSocket** (`/ws`, Spring's `TextWebSocketHandler`), not STOMP. The game has one kind of conversation, a client and a room, and STOMP's destinations and broker would add a layer between the rules and the wire. Messages are JSON with a `type` field: one sealed interface per direction (`ClientMessage`, `ServerMessage`) and one record per message. The compiler then lists every message a `switch` must handle.
- **The server refuses other origins** on the handshake (`semaphore.allowed-origins`, `https://play.didelo.fr` by default). A message it cannot read gets an `error` answer and the connection stays open.
- **Health only** is exposed by the actuator (`/actuator/health`): the status page probe and the container health check need nothing else.
- **The image**: a multi-stage build, a JRE on Alpine, run as a non-root user, with nothing written outside `/tmp`, so it runs with a read-only file system. It is pushed to GHCR on every merge to `main`, like the design system.
- **Tests**: integration tests start the application on a random port and talk to it with the JDK's own HTTP and WebSocket clients, so they need no test dependency beyond Spring Boot's.

## Consequences

- Checked in a container started like in production (read-only file system, no capabilities, 384 MiB): it is healthy, answers an echo over the WebSocket and uses about 125 MiB idle.
- The `ClientMessage` and `ServerMessage` records are the contract the front validates with Zod ([#4](https://github.com/Didelo-lilian/semaphore/issues/4)).
- With several replicas the rooms are kept in Redis, not in the replica that created them ([ADR 0008](0008-rooms-in-redis-shared-by-replicas.md)).
- Spring Boot 4 uses Jackson 3 (`tools.jackson`); the annotations are still `com.fasterxml.jackson.annotation`.
