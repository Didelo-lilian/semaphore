import type { Player } from "../protocol.ts";

/** How many of the people in the room are connected, and how many watch without a seat. A bot is never away: it is not counted. */
export const Presence = ({
  players,
  spectators = 0,
}: {
  players: Player[];
  spectators?: number;
}) => {
  const people = players.filter((player) => player.bot === null);
  const online = people.filter((player) => player.connected).length;
  const watching =
    spectators === 1
      ? "1 personne regarde"
      : `${spectators} personnes regardent`;
  return (
    <p className="sem-presence">
      {online === people.length
        ? `${online} en ligne`
        : `${online} sur ${people.length} en ligne`}
      {spectators > 0 && ` · ${watching}`}
    </p>
  );
};
