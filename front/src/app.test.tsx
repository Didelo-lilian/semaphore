import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { GameClient } from "./client.ts";
import { FakeSocket } from "./fake-socket.ts";
import joined from "./fixtures/joined-lobby.json";
import lookout from "./fixtures/room-lookout.json";
import signaler from "./fixtures/room-signaler.json";
import { GameProvider } from "./game.tsx";
import type { RoomView } from "./protocol.ts";
import { createAppRouter } from "./router.tsx";
import type { Session, SessionStore } from "./session.ts";

const memoryStore = (initial: Session | null = null) => {
  let session = initial;
  return {
    load: () => session,
    save: (next) => {
      session = next;
    },
    clear: () => {
      session = null;
    },
  } satisfies SessionStore;
};

const mount = async (path: string, store: SessionStore = memoryStore()) => {
  const queryClient = new QueryClient();
  const client = new GameClient(
    "ws://test/ws",
    store,
    queryClient,
    () => new FakeSocket() as unknown as WebSocket,
  );
  client.start();
  const router = createAppRouter();
  await router.navigate({ to: path });
  render(
    <QueryClientProvider client={queryClient}>
      <GameProvider client={client}>
        <RouterProvider router={router} />
      </GameProvider>
    </QueryClientProvider>,
  );
  return { router, store };
};

beforeEach(() => {
  FakeSocket.all = [];
});

describe("the game in the browser", () => {
  it("creates a room and lands in its lobby", async () => {
    const { router } = await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana");
    await userEvent.click(
      screen.getByRole("button", { name: "Créer un salon" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    expect(FakeSocket.last().sent).toEqual([
      { type: "createRoom", name: "Ana" },
    ]);
    FakeSocket.last().receive(joined);
    expect(await screen.findByText(joined.room.code)).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/salon/${joined.room.code}`);
  });

  it("turns an invitation link into a join with the code already known", async () => {
    await mount("/?room=usq2h");
    expect(await screen.findByText("USQ2H")).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Ton nom"), "Ben");
    await userEvent.click(
      screen.getByRole("button", { name: "Rejoindre le salon" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    expect(FakeSocket.last().sent).toEqual([
      { type: "joinRoom", code: "USQ2H", name: "Ben" },
    ]);
  });

  it("shows the refusal of the server on the page that asked", async () => {
    await mount("/?room=USQ2H");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ben");
    await userEvent.click(
      screen.getByRole("button", { name: "Rejoindre le salon" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    FakeSocket.last().receive({ type: "error", reason: "no such room" });
    expect(
      await screen.findByText("Ce salon n'existe pas, ou plus."),
    ).toBeInTheDocument();
  });

  it("sends a visitor of a room link without a seat to the invitation", async () => {
    const { router } = await mount(`/salon/${joined.room.code}`);
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(router.state.location.search).toEqual({ room: joined.room.code });
  });

  it("plays a whole game as a lookout, from the first turn to the end", async () => {
    const seat = { code: lookout.room.code, token: "t" };
    const store = memoryStore(seat);
    // What the server sends this lookout of Bâbord, one view per change, the way ADR 0003 orders them.
    const view = (version: number, changes: Partial<RoomView>) => ({
      type: "roomUpdated",
      room: { ...lookout.room, version, ...changes },
    });
    const board = lookout.room.board;
    const { router } = await mount(`/salon/${seat.code}`, store);
    const socket = FakeSocket.last();
    socket.accept();
    socket.receive({
      type: "joined",
      playerId: lookout.room.you,
      token: seat.token,
      room: lookout.room,
    });
    expect(socket.sent).toEqual([{ type: "rejoin", ...seat }]);
    expect(
      await screen.findByText("Le Signaleur de Tribord prépare son signal."),
    ).toBeInTheDocument();

    socket.receive(
      view(10, {
        phase: "GUESS",
        signal: { word: "mer", number: 1 },
        revealsLeft: 2,
      }),
    );
    expect(
      await screen.findByText("Les Veilleurs de Tribord cherchent."),
    ).toBeInTheDocument();
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);

    socket.receive(view(11, { phase: "SIGNAL", turn: "BABORD", signal: null }));
    expect(
      await screen.findByText("Le Signaleur de Bâbord prépare son signal."),
    ).toBeInTheDocument();

    socket.receive(
      view(12, {
        phase: "GUESS",
        turn: "BABORD",
        signal: { word: "voyage", number: 2 },
        revealsLeft: 3,
      }),
    );
    await userEvent.click(
      await screen.findByRole("button", { name: board[0]?.word }),
    );
    await waitFor(() =>
      expect(socket.sent).toContainEqual({ type: "reveal", index: 0 }),
    );
    socket.receive(
      view(13, {
        phase: "GUESS",
        turn: "BABORD",
        signal: { word: "voyage", number: 2 },
        revealsLeft: 2,
        board: board.map((card, index) =>
          index === 0 ? { ...card, revealed: true, kind: "BABORD" } : card,
        ),
      }),
    );
    await waitFor(() =>
      expect(document.querySelectorAll(".sem-word-button")).toHaveLength(23),
    );

    await userEvent.click(screen.getByRole("button", { name: "Passer" }));
    await waitFor(() => expect(socket.sent).toContainEqual({ type: "pass" }));
    socket.receive(
      view(14, { phase: "SIGNAL", turn: "TRIBORD", signal: null }),
    );

    socket.receive(
      view(15, {
        phase: "ENDED",
        turn: "TRIBORD",
        winner: "TRIBORD",
        board: (signaler.room as RoomView).board,
      }),
    );
    expect(
      await screen.findByRole("heading", { name: "Tribord gagne" }),
    ).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Retour à l'accueil" }),
    );
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(store.load()).toBeNull();
  });

  it("gets its seat back after a reload", async () => {
    const store = memoryStore({ code: joined.room.code, token: joined.token });
    await mount(`/salon/${joined.room.code}`, store);
    expect(screen.getByText("Connexion au salon…")).toBeInTheDocument();
    FakeSocket.last().accept();
    FakeSocket.last().receive(joined);
    expect(await screen.findByText("Salon")).toBeInTheDocument();
  });
});
