import { Alert, Button, Dialog } from "@didelo/design-system";
import { useEffect, useState } from "react";
import type { Card, RoomView, Team } from "../protocol.ts";
import { explain } from "../reasons.ts";
import { Presence } from "./Presence.tsx";
import { SignalForm } from "./SignalForm.tsx";
import { TEAM_NAME, TeamMark } from "./TeamMark.tsx";

const TEAMS: Team[] = ["BABORD", "TRIBORD"];

const describe = (card: Card) => {
  const state = card.revealed ? "révélé" : "caché";
  const kind =
    card.kind === null
      ? ""
      : `, ${{ BABORD: "Bâbord", TRIBORD: "Tribord", CALM: "eaux calmes", REEF: "récif" }[card.kind]}`;
  return `${card.word}, ${state}${kind}`;
};

/**
 * The grid as one player sees it: only a Signaleur sees the kind of a hidden word. With `onReveal`, a hidden word
 * is a button: the lookouts of the team in play pick their words there. While `busy` the buttons are `aria-disabled`
 * rather than `disabled`, so the focus of a keyboard user stays where it is.
 */
export const Board = ({
  board,
  busy = false,
  onReveal,
}: {
  board: Card[];
  busy?: boolean;
  onReveal?: (index: number) => void;
}) => (
  <ol className="sem-board" aria-label="Grille">
    {board.map((card, index) => (
      <li
        key={card.word}
        className="sem-word"
        data-kind={card.kind ?? "hidden"}
        data-revealed={card.revealed}
        aria-label={describe(card)}
      >
        {card.kind !== null && (
          <span className="sem-word-mark">
            <TeamMark kind={card.kind} size={14} />
          </span>
        )}
        {onReveal !== undefined && !card.revealed ? (
          <button
            type="button"
            className="sem-word-button"
            aria-disabled={busy}
            onClick={busy ? undefined : () => onReveal(index)}
          >
            {card.word}
          </button>
        ) : (
          <span>{card.word}</span>
        )}
      </li>
    ))}
  </ol>
);

/**
 * Counts down from what the server said when it built the view, so the browser's clock only measures the time since:
 * a new view (new version) starts a new count.
 */
const Countdown = ({ seconds }: { seconds: number }) => {
  const [left, setLeft] = useState(seconds);
  useEffect(() => {
    const start = performance.now();
    const timer = setInterval(
      () =>
        setLeft(
          Math.max(0, Math.ceil(seconds - (performance.now() - start) / 1000)),
        ),
      1000,
    );
    return () => clearInterval(timer);
  }, [seconds]);
  return (
    <p className="sem-countdown" role="timer">
      Le tour passe dans {left} s.
    </p>
  );
};

interface Props {
  room: RoomView;
  busy: boolean;
  error: string | null;
  onSignal: (word: string, number: number) => void;
  onReveal: (index: number) => void;
  onPass: () => void;
  onLeave: () => void;
}

export const Table = ({
  room,
  busy,
  error,
  onSignal,
  onReveal,
  onPass,
  onLeave,
}: Props) => {
  const [confirmingLeave, setConfirmingLeave] = useState(false);
  const inPlay = room.players.some(
    (player) => player.id === room.you && player.team === room.turn,
  );
  const signaler = room.players.some(
    (player) => player.id === room.you && player.signaler,
  );
  const canSignal = room.phase === "SIGNAL" && inPlay && signaler;
  const canReveal = room.phase === "GUESS" && inPlay && !signaler;
  const turn = room.turn === null ? "" : TEAM_NAME[room.turn];
  const ended = room.phase === "ENDED";
  const inPlayers = room.players.filter((player) => player.team === room.turn);
  const absentSignaler = inPlayers.find(
    (player) => player.signaler && !player.connected,
  );
  const lookoutsAway = inPlayers
    .filter((player) => !player.signaler)
    .every((player) => !player.connected);

  let hint: string;
  if (ended) {
    hint = "La partie est terminée.";
  } else if (room.phase === "SIGNAL" && absentSignaler !== undefined) {
    hint = `${absentSignaler.name}, le Signaleur de ${turn}, est hors ligne : la partie l'attend.`;
  } else if (room.phase === "GUESS" && lookoutsAway) {
    hint = `Les Veilleurs de ${turn} sont hors ligne : la partie les attend.`;
  } else if (canSignal) {
    hint = "À toi : envoie un signal à tes Veilleurs.";
  } else if (room.phase === "SIGNAL") {
    hint = `Le Signaleur de ${turn} prépare son signal.`;
  } else if (canReveal) {
    hint = `À toi : touche un mot, encore ${room.revealsLeft} au plus, ou passe.`;
  } else if (inPlay) {
    hint = "Tes Veilleurs cherchent…";
  } else {
    hint = `Les Veilleurs de ${turn} cherchent.`;
  }

  return (
    <section className="sem-table" aria-labelledby="table-title">
      <header className="sem-lobby-head">
        <div>
          <h1 id="table-title">
            {ended && room.winner !== null
              ? `${TEAM_NAME[room.winner]} gagne`
              : `Tour de ${turn}`}
          </h1>
          <output className="sem-hint">
            <span>{hint}</span>
            {room.phase === "GUESS" && room.signal !== null && (
              <span className="sem-sr-only">
                {" "}
                Signal : {room.signal.word}, {room.signal.number}.
              </span>
            )}
          </output>
          {room.turnPassesIn !== null && (
            <Countdown key={room.version} seconds={room.turnPassesIn} />
          )}
          <Presence players={room.players} />
        </div>
        <Button
          variant="text"
          onClick={ended ? onLeave : () => setConfirmingLeave(true)}
        >
          {ended ? "Retour à l'accueil" : "Quitter"}
        </Button>
      </header>

      {room.signal !== null && room.turn !== null && (
        <p className="sem-pennant" data-team={room.turn} aria-hidden="true">
          <TeamMark kind={room.turn} size={16} />
          <span className="sem-pennant-word">{room.signal.word}</span>
          <span className="sem-pennant-number">{room.signal.number}</span>
        </p>
      )}

      <div className="sem-teams">
        {TEAMS.map((team) => (
          <section
            key={team}
            className="sem-panel"
            data-theme="light"
            data-team={team}
            aria-label={`Équipe ${TEAM_NAME[team]}`}
          >
            <h2>
              <TeamMark kind={team} /> {TEAM_NAME[team]}
              <span className="sem-count" title="Drapeaux restants">
                ⚑ {room.flags[team]}
              </span>
            </h2>
            <ul className="sem-roster">
              {room.players
                .filter((player) => player.team === team)
                .map((player) => (
                  <li key={player.id} data-online={player.connected}>
                    {player.name}
                    {player.id === room.you ? " (toi)" : ""}
                    {player.signaler ? " · Signaleur" : ""}
                    {player.connected ? "" : " · hors ligne"}
                  </li>
                ))}
            </ul>
          </section>
        ))}
      </div>

      {error !== null && <Alert severity="error">{explain(error)}</Alert>}
      {canSignal && <SignalForm busy={busy} onSignal={onSignal} />}
      <Board
        board={room.board}
        busy={busy}
        onReveal={canReveal ? onReveal : undefined}
      />
      {canReveal && (
        <Button variant="outlined" disabled={busy} onClick={onPass}>
          Passer
        </Button>
      )}

      <Dialog
        open={confirmingLeave}
        title="Quitter la partie ?"
        onClose={() => setConfirmingLeave(false)}
        actions={
          <>
            <Button variant="text" onClick={() => setConfirmingLeave(false)}>
              Rester
            </Button>
            <Button variant="contained" onClick={onLeave}>
              Quitter
            </Button>
          </>
        }
      >
        Tu ne pourras pas reprendre ta place.
        {signaler &&
          " Tu es Signaleur : sans toi, ton équipe ne peut plus jouer et finira par perdre la partie."}
      </Dialog>
    </section>
  );
};
