import { Alert, Button, Chip } from "@didelo/design-system";
import { useState } from "react";
import type { Player, RoomView, Team } from "../protocol.ts";
import { explain } from "../reasons.ts";
import { TEAM_NAME, TeamMark } from "./TeamMark.tsx";

const MIN_PER_TEAM = 2;
const TEAMS: Team[] = ["BABORD", "TRIBORD"];

interface Props {
  room: RoomView;
  error: string | null;
  onJoinTeam: (team: Team) => void;
  onStart: () => void;
  onLeave: () => void;
}

export const PlayerChip = ({
  player,
  you,
}: {
  player: Player;
  you: boolean;
}) => (
  <li>
    <Chip
      label={[
        player.name,
        you ? "(toi)" : "",
        player.host ? "· hôte" : "",
        player.connected ? "" : "· hors ligne",
      ]
        .filter(Boolean)
        .join(" ")}
      variant={you ? "filled" : "outlined"}
    />
  </li>
);

export const Lobby = ({ room, error, onJoinTeam, onStart, onLeave }: Props) => {
  const [copied, setCopied] = useState(false);
  const me = room.players.find((player) => player.id === room.you);
  const link = `${window.location.origin}/?room=${room.code}`;
  const onTheDock = room.players.filter((player) => player.team === null);
  const ready =
    onTheDock.length === 0 &&
    TEAMS.every(
      (team) =>
        room.players.filter((p) => p.team === team).length >= MIN_PER_TEAM,
    );

  const copy = async () => {
    await navigator.clipboard?.writeText(link);
    setCopied(true);
    setTimeout(() => setCopied(false), 2_000);
  };

  return (
    <section className="sem-lobby" aria-labelledby="lobby-title">
      <header className="sem-lobby-head">
        <div>
          <h1 id="lobby-title">Salon</h1>
          <p className="sem-code sem-mono">{room.code}</p>
        </div>
        <div className="sem-actions">
          <Button variant="outlined" onClick={copy}>
            {copied ? "Lien copié" : "Copier le lien d'invitation"}
          </Button>
          <Button variant="text" onClick={onLeave}>
            Quitter
          </Button>
        </div>
      </header>

      {error !== null && <Alert severity="error">{explain(error)}</Alert>}

      <div className="sem-teams">
        {TEAMS.map((team) => {
          const members = room.players.filter((player) => player.team === team);
          return (
            <section
              key={team}
              className="sem-panel"
              data-theme="light"
              data-team={team}
              aria-label={`Équipe ${TEAM_NAME[team]}`}
            >
              <h2>
                <TeamMark kind={team} /> {TEAM_NAME[team]}
                <span className="sem-count">{members.length}</span>
              </h2>
              <ul className="sem-players">
                {members.map((player) => (
                  <PlayerChip
                    key={player.id}
                    player={player}
                    you={player.id === room.you}
                  />
                ))}
              </ul>
              {me?.team === team ? (
                <p className="sem-in-team">✓ Tu es dans cette équipe</p>
              ) : (
                <Button variant="outlined" onClick={() => onJoinTeam(team)}>
                  Rejoindre {TEAM_NAME[team]}
                </Button>
              )}
            </section>
          );
        })}
      </div>

      {onTheDock.length > 0 && (
        <section aria-label="Sans équipe">
          <h2 className="sem-dock-title">Sur le quai</h2>
          <ul className="sem-players">
            {onTheDock.map((player) => (
              <PlayerChip
                key={player.id}
                player={player}
                you={player.id === room.you}
              />
            ))}
          </ul>
        </section>
      )}

      <footer className="sem-start">
        {me?.host ? (
          <>
            <Button
              variant="contained"
              size="large"
              disabled={!ready}
              onClick={onStart}
            >
              Lancer la partie
            </Button>
            {!ready && (
              <p className="sem-hint">
                Tout le monde doit avoir une équipe, avec au moins{" "}
                {MIN_PER_TEAM} joueurs chacune.
              </p>
            )}
          </>
        ) : (
          <p className="sem-hint">
            En attente de l'hôte pour lancer la partie…
          </p>
        )}
      </footer>
    </section>
  );
};
