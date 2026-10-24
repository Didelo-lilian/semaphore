import { skipToken, useMutation, useQuery } from "@tanstack/react-query";
import {
  createContext,
  type ReactNode,
  useContext,
  useSyncExternalStore,
} from "react";
import { type GameClient, roomKey } from "./client.ts";
import type { ClientMessage, RoomView } from "./protocol.ts";

const GameContext = createContext<GameClient | null>(null);

export const GameProvider = ({
  client,
  children,
}: {
  client: GameClient;
  children: ReactNode;
}) => <GameContext value={client}>{children}</GameContext>;

export const useGame = () => {
  const client = useContext(GameContext);
  if (client === null) {
    throw new Error("useGame needs a GameProvider");
  }
  const snapshot = useSyncExternalStore(client.subscribe, client.getSnapshot);
  return { client, snapshot };
};

/** The room the server last described, or null: the socket writes the cache, nothing fetches. */
export const useRoom = () => {
  const { data } = useQuery<RoomView | null>({
    queryKey: roomKey,
    queryFn: skipToken,
    staleTime: Number.POSITIVE_INFINITY,
  });
  return data ?? null;
};

/** One command, one pending state, one error: the answer of the server to that very command. */
export const useCommand = (onSuccess?: (room: RoomView) => void) => {
  const { client } = useGame();
  return useMutation({
    mutationFn: (message: ClientMessage) => client.request(message),
    onSuccess,
    // Commands wait for their answer in order: two at once would swap their answers.
    scope: { id: "game" },
  });
};
