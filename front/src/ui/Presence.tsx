import type { Player } from "../protocol.ts";

/** How many of the players the room knows are connected, and how many people watch without a seat. */
export const Presence = ({
  players,
  spectators = 0,
}: {
  players: Player[];
  spectators?: number;
}) => {
  const online = players.filter((player) => player.connected).length;
  return (
    <p className="sem-presence">
      {online === players.length
        ? `${online} en ligne`
        : `${online} sur ${players.length} en ligne`}
      {spectators === 0 ? "" : ` · ${watching(spectators)}`}
    </p>
  );
};

const watching = (count: number) =>
  count === 1 ? "1 personne regarde" : `${count} personnes regardent`;
