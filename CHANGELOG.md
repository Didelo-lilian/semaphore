# Changelog

## [0.4.0](https://github.com/Didelo-lilian/semaphore/compare/v0.3.0...v0.4.0) (2026-10-25)


### Features

* close a connection that asks for five unknown rooms ([9b36b75](https://github.com/Didelo-lilian/semaphore/commit/9b36b75bc009e93277b7fb304730b4835065795d))
* **front:** animate card reveals and player presence ([#42](https://github.com/Didelo-lilian/semaphore/issues/42)) ([479a054](https://github.com/Didelo-lilian/semaphore/commit/479a054bad488fb1b22cf4926e570cfd2a1a5859))
* keep the rooms in Redis, shared by the replicas ([4587a72](https://github.com/Didelo-lilian/semaphore/commit/4587a72b8d0d8f593cf790bb5cb5e2a0ca6f187b))


### Bug fixes

* harden the rooms in Redis before two replicas go live ([e370b6a](https://github.com/Didelo-lilian/semaphore/commit/e370b6aef1019caf3fd9799ff43f190de9394579))


### Documentation

* keep the rooms in Redis, shared by the replicas ([#39](https://github.com/Didelo-lilian/semaphore/issues/39)) ([06badc0](https://github.com/Didelo-lilian/semaphore/commit/06badc03fa94313d52cc15447dc115a5c15eb9e9))

## [0.3.0](https://github.com/Didelo-lilian/semaphore/compare/v0.2.0...v0.3.0) (2026-10-24)


### Features

* let a visitor watch a game in progress ([#33](https://github.com/Didelo-lilian/semaphore/issues/33)) ([4581bb8](https://github.com/Didelo-lilian/semaphore/commit/4581bb84c20b190b9b690d6fecb0255a29c66a7a))
* pass the turn when a Signaleur is away ([#32](https://github.com/Didelo-lilian/semaphore/issues/32)) ([4a2751a](https://github.com/Didelo-lilian/semaphore/commit/4a2751a3e835b16f5dfeacd8d675817565525a6b))
* show who is online and keep a lobby seat through a short disconnect ([#31](https://github.com/Didelo-lilian/semaphore/issues/31)) ([e7f3602](https://github.com/Didelo-lilian/semaphore/commit/e7f36025d4b273d288c04bdbef192e7f96ee2bb1))


### Refactoring

* **front:** drop the request id fallback from GameClient ([#24](https://github.com/Didelo-lilian/semaphore/issues/24)) ([67ef94f](https://github.com/Didelo-lilian/semaphore/commit/67ef94f51abec2daef2e5b9e608355c0531eaf60))

## [0.2.0](https://github.com/Didelo-lilian/semaphore/compare/v0.1.0...v0.2.0) (2026-10-24)


### Features

* **front:** play a full game from the table ([#17](https://github.com/Didelo-lilian/semaphore/issues/17)) ([877663e](https://github.com/Didelo-lilian/semaphore/commit/877663e077a9f1ec4c805f336fe7a99b4e8419bf))
* match each command with its answer by a request id ([#22](https://github.com/Didelo-lilian/semaphore/issues/22)) ([17f0740](https://github.com/Didelo-lilian/semaphore/commit/17f074023bddc018819a469a0c9ffcac8f464a09))


### Bug fixes

* **front:** go to the home page after leaving a room ([#21](https://github.com/Didelo-lilian/semaphore/issues/21)) ([ff64f19](https://github.com/Didelo-lilian/semaphore/commit/ff64f192a11673436a5812f5b13ee728b3c46491))

## 0.1.0 (2026-10-24)


### Features

* add the Spring Boot backend with a typed WebSocket ([#8](https://github.com/Didelo-lilian/semaphore/issues/8)) ([999b1db](https://github.com/Didelo-lilian/semaphore/commit/999b1db6cd84f18cf54b77be04079d7bd13556c3)), closes [#2](https://github.com/Didelo-lilian/semaphore/issues/2)
* **front:** add the React front and its image ([#13](https://github.com/Didelo-lilian/semaphore/issues/13)) ([b3550b9](https://github.com/Didelo-lilian/semaphore/commit/b3550b9bb3bf9f8bdf7382fe1634a5edf37a1ba8))
* rooms, the game state machine and the room protocol ([#11](https://github.com/Didelo-lilian/semaphore/issues/11)) ([fc4eab4](https://github.com/Didelo-lilian/semaphore/commit/fc4eab46ca8a87c552647f26bccae90647779815))


### Documentation

* name the game and set its rules and visual direction ([#7](https://github.com/Didelo-lilian/semaphore/issues/7)) ([a5f9ee6](https://github.com/Didelo-lilian/semaphore/commit/a5f9ee6e8f9d9cc941fb41851397b1ac72cfae41)), closes [#1](https://github.com/Didelo-lilian/semaphore/issues/1)
