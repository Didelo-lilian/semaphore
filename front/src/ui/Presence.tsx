import type { Player } from "../protocol.ts";

/** How many of the players the room knows are connected. */
export const Presence = ({ players }: { players: Player[] }) => {
  const online = players.filter((player) => player.connected).length;
  return (
    <p className="sem-presence">
      {online === players.length
        ? `${online} en ligne`
        : `${online} sur ${players.length} en ligne`}
    </p>
  );
};
