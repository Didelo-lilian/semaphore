import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GameClient } from "./client.ts";
import { FakeSocket } from "./fake-socket.ts";
import joined from "./fixtures/joined-lobby.json";
import lookout from "./fixtures/room-lookout.json";
import signaler from "./fixtures/room-signaler.json";
import spectator from "./fixtures/room-spectator.json";
import waiting from "./fixtures/room-waiting.json";
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

afterEach(() => {
  vi.unstubAllGlobals();
});

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status });

// The server of /api/me: `me` answers the GET (a promise to answer it late), `put` the PUT (the name
// is echoed when it is 200).
const api = (me: Response | Promise<Response>, put?: Response) => {
  const calls = vi.fn(async (_url: string, init?: RequestInit) =>
    init?.method === "PUT"
      ? (put ?? json(200, { kind: "guest", id: "g", name: "?" }))
      : (await me).clone(),
  );
  vi.stubGlobal("fetch", calls);
  return calls;
};

const methods = (calls: ReturnType<typeof api>) =>
  calls.mock.calls.map(([, init]) => init?.method ?? "GET");

const join = async (room: RoomView) => {
  const seat = { code: room.code, token: "t" };
  await mount(`/salon/${room.code}`, memoryStore(seat));
  const socket = FakeSocket.last();
  socket.accept();
  socket.answer({
    type: "joined",
    playerId: room.you,
    token: seat.token,
    room,
  });
  return socket;
};

const lookoutGuessing = {
  ...(lookout.room as RoomView),
  phase: "GUESS" as const,
  turn: "BABORD" as const,
  signal: { word: "voyage", number: 2 },
  revealsLeft: 2,
};

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
      { type: "createRoom", id: "1", name: "Ana" },
    ]);
    FakeSocket.last().answer(joined);
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
      { type: "joinRoom", id: "1", code: "USQ2H", name: "Ben" },
    ]);
  });

  it("tells in French that the server closed the connection for too many unknown rooms", async () => {
    await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ben");
    await userEvent.type(screen.getByLabelText("Code du salon"), "ABCDE");
    await userEvent.click(screen.getByRole("button", { name: "Rejoindre" }));
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    act(() => FakeSocket.last().drop("too many unknown rooms"));
    expect(
      await screen.findByText(/Trop de codes inconnus/),
    ).toBeInTheDocument();
  });

  it("shows the refusal of the server on the page that asked", async () => {
    await mount("/?room=USQ2H");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ben");
    await userEvent.click(
      screen.getByRole("button", { name: "Rejoindre le salon" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    FakeSocket.last().answer({ type: "error", reason: "no such room" });
    expect(
      await screen.findByText("Ce salon n'existe pas, ou plus."),
    ).toBeInTheDocument();
  });

  it("sends a visitor of a room link without a seat to the invitation", async () => {
    const { router } = await mount(`/salon/${joined.room.code}`);
    await waitFor(() => expect(router.state.location.pathname).toBe("/"));
    expect(router.state.location.search).toEqual({ room: joined.room.code });
  });

  it("watches a game from an invitation link, with no seat to get back", async () => {
    const { router, store } = await mount(`/?room=${spectator.room.code}`);
    await userEvent.click(
      await screen.findByRole("button", { name: "Regarder la partie" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    expect(FakeSocket.last().sent).toEqual([
      { type: "watch", id: "1", code: spectator.room.code },
    ]);
    FakeSocket.last().answer(spectator);

    expect(
      await screen.findByText("Tu regardes la partie sans y jouer."),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(
      `/salon/${spectator.room.code}`,
    );
    expect(screen.getByText("4 en ligne · 1 personne regarde")).toBeVisible();
    expect(screen.queryByLabelText("Ton signal")).toBeNull();
    expect(store.load()).toBeNull();

    // A new view of the room reaches them like a player: a signal turns into a pennant.
    FakeSocket.last().receive({
      type: "roomUpdated",
      room: {
        ...spectator.room,
        version: spectator.room.version + 1,
        phase: "GUESS",
        signal: { word: "voyage", number: 2 },
        revealsLeft: 3,
      },
    });
    expect(await screen.findByText("Signal : voyage, 2.")).toBeInTheDocument();
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);
  });

  it("goes back to the invitation, saying why, when a spectator loses the connection", async () => {
    const { router } = await mount(`/?room=${spectator.room.code}`);
    await userEvent.click(
      await screen.findByRole("button", { name: "Regarder la partie" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    FakeSocket.last().answer(spectator);
    await screen.findByText("Tu regardes la partie sans y jouer.");

    act(() => FakeSocket.last().drop());

    expect(
      await screen.findByText("Connexion perdue, réessaie."),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    expect(router.state.location.search).toEqual({ room: spectator.room.code });
  });

  it("tells a visitor why a room cannot be watched, in French", async () => {
    await mount("/?room=USQ2H");
    await userEvent.click(
      await screen.findByRole("button", { name: "Regarder la partie" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
    FakeSocket.last().answer({
      type: "error",
      reason: "the game has not started",
    });
    expect(
      await screen.findByText(
        "La partie n'a pas encore commencé : rejoins le salon pour jouer.",
      ),
    ).toBeInTheDocument();

    await userEvent.click(
      screen.getByRole("button", { name: "Regarder la partie" }),
    );
    FakeSocket.last().answer({ type: "error", reason: "too many spectators" });
    expect(
      await screen.findByText("Trop de monde regarde déjà cette partie."),
    ).toBeInTheDocument();
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
    socket.answer({
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
      expect(socket.sent).toContainEqual({ type: "reveal", id: "1", index: 0 }),
    );
    socket.answer(
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
    await waitFor(() =>
      expect(socket.sent).toContainEqual({ type: "pass", id: "2" }),
    );
    socket.answer(view(14, { phase: "SIGNAL", turn: "TRIBORD", signal: null }));

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
    expect(router.state.location.search).toEqual({});
    expect(store.load()).toBeNull();
  });

  it("leaves the lobby for the home page, not for the invitation to the room just left", async () => {
    const store = memoryStore({ code: joined.room.code, token: joined.token });
    const { router } = await mount(`/salon/${joined.room.code}`, store);
    FakeSocket.last().accept();
    FakeSocket.last().answer(joined);
    await userEvent.click(
      await screen.findByRole("button", { name: "Quitter" }),
    );
    expect(
      await screen.findByRole("button", { name: "Créer un salon" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/");
    expect(router.state.location.search).toEqual({});
    expect(screen.queryByText(joined.room.code)).toBeNull();
    expect(store.load()).toBeNull();
  });

  it("counts down while the game waits for an absent Signaleur, then shows the turn that passed", async () => {
    const room = waiting.room as RoomView;
    const socket = await join(room);
    expect(await screen.findByRole("timer")).toHaveTextContent(
      "Le tour passe dans 60 s.",
    );

    socket.receive({
      type: "roomUpdated",
      room: {
        ...room,
        version: room.version + 1,
        turn: "BABORD",
        turnPassesIn: null,
      },
    });

    expect(
      await screen.findByText("Le Signaleur de Bâbord prépare son signal."),
    ).toBeInTheDocument();
    expect(screen.queryByRole("timer")).toBeNull();
  });

  it("sends the signal of the Signaleur and waits for the lookouts", async () => {
    const room = { ...(signaler.room as RoomView), turn: "BABORD" as const };
    const socket = await join(room);
    await userEvent.type(await screen.findByLabelText("Ton signal"), "voyage");
    await userEvent.click(screen.getByRole("button", { name: "2" }));
    await userEvent.click(
      screen.getByRole("button", { name: "Envoyer le signal" }),
    );
    await waitFor(() =>
      expect(socket.sent).toContainEqual({
        type: "signal",
        id: "1",
        word: "voyage",
        number: 2,
      }),
    );
    socket.answer({
      type: "roomUpdated",
      room: {
        ...room,
        version: room.version + 1,
        phase: "GUESS",
        signal: { word: "voyage", number: 2 },
        revealsLeft: 3,
      },
    });
    expect(await screen.findByText("Tes Veilleurs cherchent…")).toBeVisible();
    expect(screen.queryByLabelText("Ton signal")).toBeNull();
  });

  it("comes back in the middle of a guess with the signal and the revealed words", async () => {
    const room = {
      ...lookoutGuessing,
      board: lookoutGuessing.board.map((card, index) =>
        index === 0
          ? { ...card, revealed: true, kind: "BABORD" as const }
          : card,
      ),
    };
    await join(room);
    expect(await screen.findByText(/encore 2 au plus/)).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent("Signal : voyage, 2.");
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(23);
  });

  it("forgets a refusal once the room moves on", async () => {
    const socket = await join(lookoutGuessing);
    await userEvent.click(
      await screen.findByRole("button", {
        name: lookoutGuessing.board[0]?.word,
      }),
    );
    await waitFor(() =>
      expect(socket.sent).toContainEqual({ type: "reveal", id: "1", index: 0 }),
    );
    socket.answer({
      type: "error",
      reason: "only the lookouts of the team in play can do that",
    });
    const refusal =
      "Seuls les Veilleurs de l'équipe qui joue peuvent faire ça.";
    expect(await screen.findByText(refusal)).toBeVisible();

    socket.receive({
      type: "roomUpdated",
      room: {
        ...lookoutGuessing,
        version: lookoutGuessing.version + 1,
        phase: "SIGNAL",
        turn: "TRIBORD",
        signal: null,
      },
    });
    await waitFor(() => expect(screen.queryByText(refusal)).toBeNull());
  });

  it("gets its seat back after a reload", async () => {
    const store = memoryStore({ code: joined.room.code, token: joined.token });
    await mount(`/salon/${joined.room.code}`, store);
    expect(screen.getByText("Connexion au salon…")).toBeInTheDocument();
    FakeSocket.last().accept();
    FakeSocket.last().answer(joined);
    expect(await screen.findByText("Salon")).toBeInTheDocument();
  });
});

describe("the name of a guest", () => {
  const create = async () => {
    await userEvent.click(
      screen.getByRole("button", { name: "Créer un salon" }),
    );
    await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
    FakeSocket.last().accept();
  };

  const put = (calls: ReturnType<typeof api>) =>
    calls.mock.calls.find(([, init]) => init?.method === "PUT")?.[1]?.body;

  it("comes back from the server, says so, and is saved again on entering a room", async () => {
    const calls = api(json(200, { kind: "guest", id: "g", name: "Ana" }));
    await mount("/");
    await waitFor(() =>
      expect(screen.getByLabelText("Ton nom")).toHaveValue("Ana"),
    );
    expect(screen.getByText(/retenu sur cet appareil/)).toBeInTheDocument();
    await create();
    // Another tab may have renamed the guest since this page read it.
    expect(methods(calls)).toEqual(["GET", "PUT"]);
    expect(put(calls)).toBe('{"name":"Ana"}');
    expect(FakeSocket.last().sent).toEqual([
      { type: "createRoom", id: "1", name: "Ana" },
    ]);
  });

  it("is saved before the room is entered at the first visit, which promises nothing before", async () => {
    const calls = api(
      json(200, { kind: "anonymous" }),
      json(200, { kind: "guest", id: "g", name: "Ben" }),
    );
    await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ben");
    expect(screen.queryByText(/retenu sur cet appareil/)).toBeNull();
    await create();
    expect(methods(calls)).toEqual(["GET", "PUT"]);
    expect(put(calls)).toBe('{"name":"Ben"}');
    expect(FakeSocket.last().sent).toEqual([
      { type: "createRoom", id: "1", name: "Ben" },
    ]);
  });

  it("is saved again when it changed", async () => {
    const calls = api(json(200, { kind: "guest", id: "g", name: "Ana" }));
    await mount("/");
    const field = await screen.findByLabelText("Ton nom");
    await waitFor(() => expect(field).toHaveValue("Ana"));
    await userEvent.clear(field);
    await userEvent.type(field, "Zoé");
    await create();
    expect(put(calls)).toBe('{"name":"Zoé"}');
  });

  it.each([404, 429, 503])(
    "is saved all the same when this page could not read it (%i)",
    async (status) => {
      const calls = api(json(status, { error: "no" }));
      await mount("/");
      await userEvent.type(await screen.findByLabelText("Ton nom"), "Bob");
      expect(screen.queryByText(/retenu sur cet appareil/)).toBeNull();
      await create();
      expect(put(calls)).toBe('{"name":"Bob"}');
      expect(FakeSocket.last().sent).toEqual([
        { type: "createRoom", id: "1", name: "Bob" },
      ]);
    },
  );

  it("does not keep the player out of a room when the server refuses to save it", async () => {
    api(json(200, { kind: "anonymous" }), json(429, { error: "slow down" }));
    await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ben");
    await create();
    expect(FakeSocket.last().sent).toEqual([
      { type: "createRoom", id: "1", name: "Ben" },
    ]);
  });

  describe("when the server answers late", () => {
    const late = () => {
      let answer = (_: Response) => {};
      const response = new Promise<Response>((resolve) => {
        answer = resolve;
      });
      return { response, answer };
    };

    it("shows the form at once and fills the name when it arrives", async () => {
      const { response, answer } = late();
      api(response);
      await mount("/");
      expect(await screen.findByLabelText("Ton nom")).toHaveValue("");
      answer(json(200, { kind: "guest", id: "g", name: "Ana" }));
      await waitFor(() =>
        expect(screen.getByLabelText("Ton nom")).toHaveValue("Ana"),
      );
    });

    it("leaves alone a name the player already typed", async () => {
      const { response, answer } = late();
      const calls = api(response);
      await mount("/");
      await userEvent.type(await screen.findByLabelText("Ton nom"), "Bob");
      answer(json(200, { kind: "guest", id: "g", name: "Ana" }));
      await waitFor(() =>
        expect(screen.getByText(/retenu sur cet appareil/)).toBeInTheDocument(),
      );
      expect(screen.getByLabelText("Ton nom")).toHaveValue("Bob");
      await create();
      expect(put(calls)).toBe('{"name":"Bob"}');
    });

    it("waits for the first answer before saving, which hands out the CSRF cookie", async () => {
      const { response, answer } = late();
      const calls = api(response);
      await mount("/");
      await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana");
      await userEvent.click(
        screen.getByRole("button", { name: "Créer un salon" }),
      );
      expect(methods(calls)).toEqual(["GET"]);
      answer(json(200, { kind: "anonymous" }));
      await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
      expect(methods(calls)).toEqual(["GET", "PUT"]);
      expect(put(calls)).toBe('{"name":"Ana"}');
    });

    it("does not wait for a server that never answers", async () => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      try {
        // Hangs until the request is given up.
        vi.stubGlobal(
          "fetch",
          (_url: string, init: RequestInit) =>
            new Promise((_, reject) =>
              init.signal?.addEventListener("abort", () =>
                reject(new DOMException("aborted", "AbortError")),
              ),
            ),
        );
        await mount("/");
        await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana", {
          advanceTimers: vi.advanceTimersByTime,
        });
        await userEvent.click(
          screen.getByRole("button", { name: "Créer un salon" }),
        );
        expect(FakeSocket.all).toHaveLength(0);
        // The read of who the player is gives up, then the save does.
        await vi.advanceTimersByTimeAsync(5_000);
        await vi.advanceTimersByTimeAsync(5_000);
        await waitFor(() => expect(FakeSocket.all).toHaveLength(1));
        FakeSocket.last().accept();
        expect(FakeSocket.last().sent).toEqual([
          { type: "createRoom", id: "1", name: "Ana" },
        ]);
      } finally {
        vi.useRealTimers();
      }
    });
  });
});

describe("signing in", () => {
  type Who = Record<string, unknown>;
  const anonymous: Who = { kind: "anonymous", providers: ["github", "google"] };
  const account: Who = { kind: "account", id: "a", name: "Ana" };

  // The server of /api/me, /api/auth/logout and /api/auth/account. `refuse` is the status of the PUT of the name when
  // it is not 200, `ending` the status of the logout and the deletion: 204 and 401 leave the browser anonymous (the
  // session is gone either way), another one leaves it as it was.
  const server = (start: Who, refuse?: number, ending = 204) => {
    let who = start;
    const calls = vi.fn(async (url: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      if (method === "PUT") {
        who = { kind: "guest", id: "g", name: "Ana", providers: ["github"] };
        return refuse === undefined ? json(200, who) : json(refuse, {});
      }
      if (url !== "/api/me") {
        if (ending === 204 || ending === 401) {
          who = anonymous;
        }
        return new Response(null, { status: ending });
      }
      return json(200, who);
    });
    vi.stubGlobal("fetch", calls);
    return calls;
  };

  const requests = (calls: ReturnType<typeof server>) =>
    calls.mock.calls.map(([url, init]) => `${init?.method ?? "GET"} ${url}`);

  const leaving = () => {
    const assign = vi.fn();
    vi.stubGlobal("location", { ...window.location, assign });
    return assign;
  };

  const github = () =>
    screen.findByRole("button", { name: "Se connecter avec GitHub" });

  it("saves the name, then goes to the provider chosen", async () => {
    const calls = server(anonymous);
    await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana");
    const assign = leaving();
    await userEvent.click(await github());
    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith("/api/auth/login/github"),
    );
    expect(requests(calls)).toEqual(["GET /api/me", "PUT /api/me"]);
  });

  it("starts one login only, however many times the button is clicked", async () => {
    const calls = server(anonymous);
    await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana");
    const assign = leaving();
    const button = await github();
    await userEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    fireEvent.click(button);
    await waitFor(() => expect(assign).toHaveBeenCalledOnce());
    expect(button).toBeDisabled();
    expect(
      requests(calls).filter((call) => call === "PUT /api/me"),
    ).toHaveLength(1);
  });

  it("does not leave when the name was refused, and says so", async () => {
    server(anonymous, 429);
    await mount("/");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana");
    const assign = leaving();
    await userEvent.click(
      await screen.findByRole("button", { name: "Se connecter avec Google" }),
    );
    expect(
      await screen.findByText(/n'a pas pu être enregistré/),
    ).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
  });

  it("offers no button without a provider", async () => {
    server({ kind: "anonymous", providers: [] });
    await mount("/");
    await screen.findByLabelText("Ton nom");
    expect(screen.queryByRole("button", { name: /Se connecter/ })).toBeNull();
  });

  it.each([["gitlab"], ["constructor"]])(
    "draws no button for a provider it does not know (%s)",
    async (name) => {
      server({ kind: "anonymous", providers: [name, "github"] });
      await mount("/");
      expect(await github()).toBeInTheDocument();
      expect(
        screen.getAllByRole("button", { name: /Se connecter/ }),
      ).toHaveLength(1);
    },
  );

  it("says that a login failed, once: the address forgets it", async () => {
    server(anonymous);
    const { router } = await mount("/?room=USQ2H&login=failed");
    expect(
      await screen.findByText(/La connexion a échoué/),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(router.state.location.search).toEqual({ room: "USQ2H" }),
    );
    expect(screen.getByText(/La connexion a échoué/)).toBeInTheDocument();
  });

  it("says nothing of a login that failed to a player who is signed in", async () => {
    server(account);
    await mount("/?login=failed");
    await screen.findByText(/Connecté en tant que/);
    expect(screen.queryByText(/La connexion a échoué/)).toBeNull();
  });

  it("asks for the pseudo, then resumes the login", async () => {
    const calls = server(anonymous);
    await mount("/?login=pseudo&provider=google");
    await userEvent.type(await screen.findByLabelText("Ton nom"), "Ana");
    const assign = leaving();
    await userEvent.click(
      await screen.findByRole("button", { name: "Continuer avec Google" }),
    );
    await waitFor(() =>
      expect(assign).toHaveBeenCalledWith("/api/auth/login/google"),
    );
    expect(requests(calls)).toContain("PUT /api/me");
  });

  it.each([
    ["a provider the server does not list", "gitlab", ["github", "google"]],
    ["a provider that is off", "google", ["github"]],
    ["a provider the page does not know", "gitlab", ["gitlab", "github"]],
  ])("resumes no login for %s", async (_, provider, providers) => {
    server({ kind: "anonymous", providers });
    await mount(`/?login=pseudo&provider=${provider}`);
    expect(
      await screen.findByText(/Dis-nous comment t'appeler/),
    ).not.toHaveTextContent("avec");
    expect(await github()).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Continuer/ })).toBeNull();
  });

  it("ignores a login that is not one", async () => {
    server(anonymous);
    await mount("/?login=elsewhere");
    await screen.findByLabelText("Ton nom");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  describe("with an account", () => {
    const deleteAccount = async () => {
      await userEvent.click(
        await screen.findByRole("button", { name: "Supprimer mon compte" }),
      );
      await userEvent.click(
        within(await screen.findByRole("dialog")).getByRole("button", {
          name: "Supprimer",
        }),
      );
    };

    it("shows who is signed in and signs out, leaving no name behind", async () => {
      const calls = server(account);
      await mount("/");
      expect(await screen.findByText(/Connecté en tant que/)).toHaveTextContent(
        "Ana",
      );
      await waitFor(() =>
        expect(screen.getByLabelText("Ton nom")).toHaveValue("Ana"),
      );
      expect(screen.queryByRole("button", { name: /Se connecter/ })).toBeNull();
      await userEvent.click(
        screen.getByRole("button", { name: "Se déconnecter" }),
      );
      expect(await github()).toBeInTheDocument();
      expect(requests(calls)).toContain("POST /api/auth/logout");
      expect(screen.queryByText(/Connecté en tant que/)).toBeNull();
      expect(screen.getByLabelText("Ton nom")).toHaveValue("");
    });

    it("deletes the account after a confirmation, leaving no name behind", async () => {
      const calls = server(account);
      await mount("/");
      await userEvent.click(
        await screen.findByRole("button", { name: "Supprimer mon compte" }),
      );
      expect(requests(calls)).not.toContain("DELETE /api/auth/account");
      await userEvent.click(
        within(await screen.findByRole("dialog")).getByRole("button", {
          name: "Supprimer",
        }),
      );
      expect(await github()).toBeInTheDocument();
      expect(requests(calls)).toContain("DELETE /api/auth/account");
      expect(screen.getByLabelText("Ton nom")).toHaveValue("");
    });

    it("keeps the account and says why when the edge refuses the deletion", async () => {
      server(account, undefined, 403);
      await mount("/");
      await deleteAccount();
      expect(await screen.findByRole("alert")).toHaveTextContent(
        /refusé \(code 403\) : ton compte n'est pas supprimé/,
      );
      expect(screen.getByText(/Connecté en tant que/)).toBeInTheDocument();
    });

    it("keeps the account and says so when the server fails to sign out", async () => {
      server(account, undefined, 500);
      await mount("/");
      await userEvent.click(
        await screen.findByRole("button", { name: "Se déconnecter" }),
      );
      expect(await screen.findByRole("alert")).toHaveTextContent(
        /refusé \(code 500\) : tu es toujours connecté/,
      );
    });

    it("says the server did not answer when it did not", async () => {
      const calls = server(account);
      calls.mockImplementation(async (url) => {
        if (url === "/api/me") {
          return json(200, account);
        }
        throw new TypeError("offline");
      });
      await mount("/");
      await deleteAccount();
      expect(await screen.findByRole("alert")).toHaveTextContent(
        /n'a pas répondu : ton compte n'est pas supprimé/,
      );
    });

    it("shows the browser as signed out when the session was already gone", async () => {
      server(account, undefined, 401);
      await mount("/");
      await userEvent.click(
        await screen.findByRole("button", { name: "Se déconnecter" }),
      );
      expect(await github()).toBeInTheDocument();
      expect(screen.queryByText(/Connecté en tant que/)).toBeNull();
    });

    it("shows the account inside an invitation", async () => {
      server(account);
      await mount("/?room=USQ2H");
      expect(
        await screen.findByText(/Connecté en tant que/),
      ).toBeInTheDocument();
      expect(screen.getByText("USQ2H")).toBeInTheDocument();
    });
  });
});
