import { Alert, Button, Chip } from "@didelo/design-system";
import { useState } from "react";
import { type Motion, useListMotion } from "../motion.ts";
import type { Level, Player, RoomView, Team } from "../protocol.ts";
import { explain } from "../reasons.ts";
import { LEVEL_NAME, LEVELS } from "./levels.ts";
import { Presence } from "./Presence.tsx";
import { TEAM_NAME, TeamMark } from "./TeamMark.tsx";

const MIN_PER_TEAM = 2;
const MAX_PLAYERS = 12;
const TEAMS: Team[] = ["BABORD", "TRIBORD"];

interface Props {
  room: RoomView;
  error: string | null;
  onJoinTeam: (team: Team) => void;
  onAddBot: (team: Team, signaler: boolean, level: Level) => void;
  onRemoveBot: (playerId: string) => void;
  onStart: () => void;
  onLeave: () => void;
}

/** A player who just left stays a moment, faded out and hidden from assistive technology, so the row can leave softly. */
export const PlayerChip = ({
  player,
  you,
  motion,
  onSettle,
  onRemove,
}: {
  player: Player;
  you: boolean;
  motion?: Motion;
  onSettle?: () => void;
  onRemove?: () => void;
}) => (
  <li
    data-online={player.connected}
    data-motion={motion}
    aria-hidden={motion === "leave" ? true : undefined}
    onAnimationEnd={onSettle}
  >
    <Chip
      label={[
        player.name,
        you ? "(toi)" : "",
        player.host ? "· hôte" : "",
        player.team !== null && player.signaler ? "· Signaleur" : "",
        player.bot ? `· niveau ${LEVEL_NAME[player.bot]}` : "",
        player.connected ? "" : "· hors ligne",
      ]
        .filter(Boolean)
        .join(" ")}
      variant={you ? "filled" : "outlined"}
    />
    {onRemove !== undefined && (
      <Button
        variant="text"
        size="small"
        aria-label={`Retirer ${player.name}`}
        onClick={onRemove}
      >
        Retirer
      </Button>
    )}
  </li>
);

export const Lobby = ({
  room,
  error,
  onJoinTeam,
  onAddBot,
  onRemoveBot,
  onStart,
  onLeave,
}: Props) => {
  const [copied, setCopied] = useState(false);
  const [level, setLevel] = useState<Level>("NORMAL");
  const me = room.players.find((player) => player.id === room.you);
  const link = `${window.location.origin}/?room=${room.code}`;
  const { shown, motionOf, settle } = useListMotion(
    room.players,
    (player) => player.id,
  );
  const onTheDock = room.players.filter((player) => player.team === null);
  // The dock follows `shown`, so a row fading out of it keeps its section until the fade ends and is never left behind.
  const dock = shown.filter((player) => player.team === null);
  const away = room.players.filter((player) => !player.connected);
  const teamsReady =
    onTheDock.length === 0 &&
    TEAMS.every(
      (team) =>
        room.players.filter((p) => p.team === team).length >= MIN_PER_TEAM,
    );

  const isHost = me?.host === true;
  // A backend that has no bots sends no `bot` at all: it would refuse the messages that seat one.
  const canAddBots = isHost && room.players.every((p) => p.bot !== undefined);
  const full = room.players.length >= MAX_PLAYERS;

  const chips = (players: Player[]) =>
    players.map((player) => (
      <PlayerChip
        key={player.id}
        player={player}
        you={player.id === room.you}
        motion={motionOf(player.id)}
        onSettle={() => settle(player.id)}
        onRemove={
          canAddBots && player.bot ? () => onRemoveBot(player.id) : undefined
        }
      />
    ));

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
          <Presence players={room.players} />
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

      {canAddBots && (
        <div className="sem-bot-level">
          <span id="bot-level">Niveau des bots</span>
          <fieldset className="sem-numbers" aria-labelledby="bot-level">
            {LEVELS.map((choice) => (
              <Button
                key={choice}
                variant={level === choice ? "contained" : "outlined"}
                aria-pressed={level === choice}
                onClick={() => setLevel(choice)}
              >
                {LEVEL_NAME[choice]}
              </Button>
            ))}
          </fieldset>
        </div>
      )}

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
                {chips(shown.filter((player) => player.team === team))}
              </ul>
              {me?.team === team ? (
                <p className="sem-in-team">✓ Tu es dans cette équipe</p>
              ) : (
                <Button variant="outlined" onClick={() => onJoinTeam(team)}>
                  Rejoindre {TEAM_NAME[team]}
                </Button>
              )}
              {canAddBots && (
                <div className="sem-actions">
                  <Button
                    variant="text"
                    disabled={
                      full || members.find((p) => p.signaler)?.bot != null
                    }
                    onClick={() => onAddBot(team, true, level)}
                  >
                    Ajouter un bot Signaleur
                  </Button>
                  <Button
                    variant="text"
                    disabled={full || members.length === 0}
                    onClick={() => onAddBot(team, false, level)}
                  >
                    Ajouter un bot Veilleur
                  </Button>
                </div>
              )}
            </section>
          );
        })}
      </div>

      {dock.length > 0 && (
        <section aria-label="Sans équipe">
          <h2 className="sem-dock-title">Sur le quai</h2>
          <ul className="sem-players">{chips(dock)}</ul>
        </section>
      )}

      <footer className="sem-start">
        {isHost ? (
          <>
            <Button
              variant="contained"
              size="large"
              disabled={!teamsReady || away.length > 0}
              onClick={onStart}
            >
              Lancer la partie
            </Button>
            {!teamsReady && (
              <p className="sem-hint">
                Tout le monde doit avoir une équipe, avec au moins{" "}
                {MIN_PER_TEAM} joueurs chacune.
              </p>
            )}
            {away.length > 0 && (
              <p className="sem-hint">
                {away.map((player) => player.name).join(", ")} : hors ligne.
                Lance la partie quand tout le monde est de retour.
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
