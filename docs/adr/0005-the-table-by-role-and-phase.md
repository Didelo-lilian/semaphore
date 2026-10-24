# 5. The table, by role and by phase

Date: 2026-10-24 · Status: accepted

## Context

[ADR 0003](0003-rooms-the-state-machine-and-the-protocol.md) made the server the judge of every move and [ADR 0004](0004-a-react-front-on-the-design-system.md) made the front a view of what the server says. Until now the table only showed the grid. Playing a game means that four people look at the same room and each of them must see, at once, whether it is their turn and what they can do.

## Decision

### The screen answers "who must act?" from the view alone

The room view already says everything: the `phase`, the `turn` (the team in play), who `you` are and whether you are a Signaleur. Two facts come out of it, and nothing else decides what is clickable:

- **in play**: you belong to the team whose turn it is;
- **Signaleur**: your player has `signaler` set.

| Phase | In play, Signaleur | In play, lookout | Everybody else |
|---|---|---|---|
| `SIGNAL` | the signal form | "Le Signaleur de … prépare son signal." | the same line |
| `GUESS` | "Tes Veilleurs cherchent…" | the hidden words are buttons, and "Passer" | "Les Veilleurs de … cherchent." |
| `ENDED` | the winner, the whole key, a way back home | the same | the same |

```mermaid
flowchart TD
  view(["A room view arrives"]) --> phase{"phase"}
  phase -->|LOBBY| lobby["Lobby: pick a team,<br/>the host starts"]
  phase -->|SIGNAL| sig{"Signaleur of the<br/>team in play?"}
  phase -->|GUESS| gue{"Lookout of the<br/>team in play?"}
  phase -->|ENDED| over["Winner, the whole key,<br/>back to the home page"]
  sig -->|yes| form["Signal form<br/>sends signal"]
  sig -->|no| waitsig["Waits for the Signaleur<br/>of the team in play"]
  gue -->|yes| pick["Touch a hidden word: reveal<br/>or Passer: pass"]
  gue -->|no| waitgue["Waits for the lookouts<br/>of the team in play"]
  form --> view
  pick --> view
```

Both buttons and form exist only for the player who may use them, so the server's refusals ("not your turn", "not the Signaleur") are a safety net and not something a player normally meets. The front does not replay the rules: it does not count the `n + 1` reveals, it shows how many are left (`revealsLeft`), and a signal too close to a word of the grid is judged by the server, which answers with a reason shown next to the form (`reasons.ts`). The form refuses an empty field and nothing else: what makes a valid signal (one word, letters, hyphens allowed, not too close to the grid) is the server's rule, and a copy of it in the browser would drift (a first version refused hyphenated words that the server accepts). The number is one of four buttons.

### The cards and the signal

- A hidden word becomes a button that covers its card, for the lookouts in play only. A revealed word is never one. While a command waits for its answer the word buttons are `aria-disabled` and "Passer" is disabled: the commands run one after the other ([ADR 0004](0004-a-react-front-on-the-design-system.md)), a second click would only queue behind the first. A word button is not `disabled` so that the focus of a keyboard user stays on it. The buttons are freed by the answer to the command, matched by its id: a view caused by someone else does not free them.
- The signal is shown as a pennant that carries the word and the number, in the color of the team in play, with the mark of that team (triangle or circle) before the word. As everywhere in the game, the color is never alone: a shape tells the teams apart ([ADR 0001](0001-name-rules-and-visual-direction.md)). The text sits in the rectangular part of the pennant, clear of its tip: a first version clipped the whole pennant to a triangle and cut the number off.
- A card turns over when it is revealed and the pennant is hoisted when a signal arrives. Both are one CSS animation, and `prefers-reduced-motion` turns both off.

### Refusals, leaving, screen readers

- A refusal answers the view it was sent from: when a newer view arrives, `SalonPage` forgets it, so a lookout who clicked a second too late does not keep a red message for the rest of the game. A refused signal has no new view, so it stays next to the form until the Signaleur tries again.
- "Quitter" in the middle of a game asks first, because the seat cannot be taken back and a Signaleur who leaves blocks the turn of their team; the dialog says so to a Signaleur. After the end it leads home at once.
- The line that says who must act is a live region (`<output>`), so a screen reader announces a new turn, and it carries the signal in words ("Signal : voyage, 2."); the pennant itself is hidden from assistive technology, where it would be read as "voyage2".

### Coming back in the middle of a turn needs no code

A reload or a lost connection ends in `rejoin`, and the answer is the whole current view of that player. The screen is a function of that view, so a player who comes back in the middle of a guess sees the signal, the cards already revealed and the right buttons, with nothing to restore.

## Consequences

- The tests follow the table: `screens.test.tsx` renders it for each role and phase (what is shown, what can be clicked, what it sends), and `app.test.tsx` plays a game as a lookout through the router and a fake socket, from the first turn to the winner and back home.
- A Signaleur who closes their tab blocks the turn: the server has no timeout, and the lookouts of that team can only wait or leave. The rooms keep living for 30 minutes after the last activity ([ADR 0003](0003-rooms-the-state-machine-and-the-protocol.md)).
- There is no rematch: the Signaleur is fixed for a grid ([ADR 0003](0003-rooms-the-state-machine-and-the-protocol.md)), so a new game is a new room. Rotation of the Signaleur comes with the rematch.
- Every merge to `main` of this repository redeploys the application, and the rooms are in memory: a game in progress does not survive it.
