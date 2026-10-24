import { Button } from "@didelo/design-system";
import type { Card, RoomView, Team } from "../protocol.ts";
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

/** The grid as one player sees it: only a Signaleur sees the kind of a hidden word. */
export const Board = ({ board }: { board: Card[] }) => (
  <ol className="sem-board" aria-label="Grille">
    {board.map((card) => (
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
        <span>{card.word}</span>
      </li>
    ))}
  </ol>
);

export const Table = ({
  room,
  onLeave,
}: {
  room: RoomView;
  onLeave: () => void;
}) => (
  <section className="sem-table" aria-labelledby="table-title">
    <header className="sem-lobby-head">
      <div>
        <h1 id="table-title">
          {room.phase === "ENDED" && room.winner !== null
            ? `${TEAM_NAME[room.winner]} gagne`
            : room.turn !== null
              ? `Tour de ${TEAM_NAME[room.turn]}`
              : "Salon"}
        </h1>
        <p className="sem-hint">
          {room.signal !== null
            ? `Signal : ${room.signal.word} (${room.signal.number}), encore ${room.revealsLeft} mots à révéler au plus.`
            : room.phase === "ENDED"
              ? "La partie est terminée."
              : "Le Signaleur prépare son signal."}
        </p>
      </div>
      <Button variant="text" onClick={onLeave}>
        Quitter
      </Button>
    </header>

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
                <li key={player.id}>
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

    <Board board={room.board} />
    <p className="sem-hint">
      Cette version affiche la partie : les tours se jouent dans la prochaine.
    </p>
  </section>
);
