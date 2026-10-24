import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { GameClient } from "./client.ts";
import { FakeSocket } from "./fake-socket.ts";
import joined from "./fixtures/joined-lobby.json";
import { GameProvider } from "./game.tsx";
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

  it("gets its seat back after a reload", async () => {
    const store = memoryStore({ code: joined.room.code, token: joined.token });
    await mount(`/salon/${joined.room.code}`, store);
    expect(screen.getByText("Connexion au salon…")).toBeInTheDocument();
    FakeSocket.last().accept();
    FakeSocket.last().receive(joined);
    expect(await screen.findByText("Salon")).toBeInTheDocument();
  });
});
