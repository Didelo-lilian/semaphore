# 1. Name, rules and visual direction

Date: 2026-10-24 · Status: accepted

## Context

The game is a real-time word game for two teams. Before any backend rule or screen exists it needs a name, rules that are clearly its own, and a visual direction the design system can serve.

## Decision

### Name

**Sémaphore** (`semaphore` in code and URLs). A signal is a word and a number sent to a team that cannot see the answer key, which is what a semaphore does.

### Rules

The genre is the family of word-association party games. The mechanics below are this game's own.

- **Grid**: 24 words, 6 columns by 4 rows, drawn at random from a French word list.
- **Two teams**, **Bâbord** and **Tribord** (port and starboard). Each has 8 words on the grid. The 8 others are 6 **calm waters** (neutral) and 2 **reefs**.
- **Roles**: each team has one **Signaleur**, who sees which word is which, and **Veilleurs** (lookouts), who do not. The Signaleur is chosen in the lobby and rotates each round.
- **A turn**: the Signaleur sends a **signal**: one word and a number *n* from 1 to 4. The Veilleurs then reveal words one at a time, at most *n* + 1.
  - Their own team's word: they may continue, or pass.
  - Calm waters: the turn ends.
  - The other team's word: the turn ends and that word counts for the other team.
  - A reef: the turn ends and the team **loses a flag** (a team has two).
- **End of the game**: a team wins when all its words are revealed, or when the other team has lost both its flags.
- **What the server refuses**: a signal that is, contains or shares its first four letters with a word on the grid; a number outside 1 to 4; any message from a player whose role or turn it is not. The client never decides.

What differs from the games it resembles: a reef costs a flag instead of ending the game, a team can lose on flags without having missed all its words, the signal is checked by the server against the grid, and a round rotates the Signaleur.

### Visual direction

Nautical signalling, drawn flat.

- **Shapes**: words sit on **flag cards**. A signal is hoisted as a **pennant** that carries the number. The flags a team can still lose are shown as two small flags.
- **Colors** (game tokens, defined in the game on top of the design system's theme, not added to the design system):

  | Token | Value | Use |
  |---|---|---|
  | `ink` | `#10203A` | text on paper and sand |
  | `paper` | `#F4EFE3` | unrevealed card, text on the navy |
  | `navy` | `#0B1B33` | page background |
  | `babord` | `#C2411C` | team Bâbord, white text (5.16:1) |
  | `tribord` | `#0F7480` | team Tribord, white text (5.48:1) |
  | `sand` | `#D9C9A3` | calm waters, `ink` text (9.95:1) |
  | `reef` | `#1B1F2A` | reefs, white text (16.46:1) |

- **Never color alone**: Bâbord and Tribord have almost the same luminance (1.06:1), so a color-blind player cannot tell them apart by color. Bâbord carries a **triangle** and Tribord a **circle** on every card, flag and score; the reef is hatched.
- **Type**: the design system's: Bricolage Grotesque for titles and words, Figtree for text, JetBrains Mono for room codes.
- **Motion**: a card turns over when revealed and a pennant is hoisted when a signal is sent, both short and eased. With `prefers-reduced-motion` they become an instant change.
- **Light and dark**: the game is dark (navy page) only at first; the design system's light theme is not used until the game has a light variant.

### What the design system must provide

Nothing new for v0.1: the game uses its Button, TextField, Card, Chip, Dialog and Tooltip with a game theme. Anything specific to the game (flag card, pennant, flags left) lives in the game. If a second project needs one, it moves to the design system then.

## Consequences

- The rules fit a 6×4 grid, a state machine with five states (lobby, signal, guess, round end, game end) and one message type per legal action, which is what [#3](https://github.com/Didelo-lilian/semaphore/issues/3) builds.
- A French word list with a licence that allows redistribution must be chosen before the grid can be drawn; the first version uses a short curated list.
- The palette passes WCAG AA for text; the shape rule is what makes the teams distinguishable, so every screen must keep it, and the visual-regression and accessibility checks of the front will cover it.
- Changing the rules later is cheap while the state machine is the only place that knows them.
