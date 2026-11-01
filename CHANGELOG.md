# Changelog

## [0.5.0](https://github.com/Didelo-lilian/semaphore/compare/v0.4.0...v0.5.0) (2026-11-01)


### Features

* add Postgres with Flyway migrations ([#59](https://github.com/Didelo-lilian/semaphore/issues/59)) ([f2d5105](https://github.com/Didelo-lilian/semaphore/commit/f2d510515d200b972a008eb72977397f2cef6e62))
* add the logo, the favicon and the privacy and terms pages ([#70](https://github.com/Didelo-lilian/semaphore/issues/70)) ([ad1217d](https://github.com/Didelo-lilian/semaphore/commit/ad1217d0a51a7165c5c2302af7828202d2536d43))
* give a signal at three levels from the word vectors ([#75](https://github.com/Didelo-lilian/semaphore/issues/75)) ([bc83ca3](https://github.com/Didelo-lilian/semaphore/commit/bc83ca3b3633f0396b5384f235cbca93217ec90c))
* identify guests at the WebSocket handshake and remember the pseudo in the front ([#72](https://github.com/Didelo-lilian/semaphore/issues/72)) ([b06ac50](https://github.com/Didelo-lilian/semaphore/commit/b06ac50909fbfa0fab824333a740366733e4552d))
* keep a guest's pseudo in a session shared by the replicas ([#61](https://github.com/Didelo-lilian/semaphore/issues/61)) ([83f4a0a](https://github.com/Didelo-lilian/semaphore/commit/83f4a0aaf65db7bd57e93a09a3eba2415969a964))
* load the French word vectors for the bots ([#68](https://github.com/Didelo-lilian/semaphore/issues/68)) ([6cafafe](https://github.com/Didelo-lilian/semaphore/commit/6cafafea3e6a04442d7b9720406cc4041397c7fe))
* pick cards from a signal at three levels ([#78](https://github.com/Didelo-lilian/semaphore/issues/78)) ([8e238d3](https://github.com/Didelo-lilian/semaphore/commit/8e238d38aa8cab23e6e972950b77221663d67164))
* record the games that end with an account in them ([#62](https://github.com/Didelo-lilian/semaphore/issues/62)) ([018510b](https://github.com/Didelo-lilian/semaphore/commit/018510b6ac786025af392eea3198d78b75604eff))
* sign in with GitHub or Google ([#74](https://github.com/Didelo-lilian/semaphore/issues/74)) ([c9c2736](https://github.com/Didelo-lilian/semaphore/commit/c9c2736a2695a83f79bd3da157de02eaf6f45c9c))
* sign in, sign out and delete the account from the home page ([#79](https://github.com/Didelo-lilian/semaphore/issues/79)) ([b135cf2](https://github.com/Didelo-lilian/semaphore/commit/b135cf237a8ba51b4a46a6f43c2a515b6d1960ef))


### Documentation

* add ADR 0011 on signing in with GitHub or Google ([#57](https://github.com/Didelo-lilian/semaphore/issues/57)) ([fb18097](https://github.com/Didelo-lilian/semaphore/commit/fb18097a408cbbb1b0a7a40076fc19c7878b6240))
* add ADR 0012 on finished games, profiles and the leaderboard ([#60](https://github.com/Didelo-lilian/semaphore/issues/60)) ([fc3be84](https://github.com/Didelo-lilian/semaphore/commit/fc3be846003ca8334daf3fa64f134d55b3abaf33))
* choose the French word vectors for the bots ([#56](https://github.com/Didelo-lilian/semaphore/issues/56)) ([a87e4be](https://github.com/Didelo-lilian/semaphore/commit/a87e4be56a7ad6cee752243d2147b0f9a95fdf9c))
* record how guest sessions work ([#58](https://github.com/Didelo-lilian/semaphore/issues/58)) ([3afa243](https://github.com/Didelo-lilian/semaphore/commit/3afa2436c056d2db90391c587d9feacebe90f30a))

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
