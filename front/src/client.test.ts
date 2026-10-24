import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GameClient, roomKey } from "./client.ts";
import { FakeSocket } from "./fake-socket.ts";
import joined from "./fixtures/joined-lobby.json";
import lookout from "./fixtures/room-lookout.json";
import spectator from "./fixtures/room-spectator.json";
import type { Session, SessionStore } from "./session.ts";

const memoryStore = (initial: Session | null = null) => {
  let session = initial;
  const store: SessionStore = {
    load: () => session,
    save: (next) => {
      session = next;
    },
    clear: () => {
      session = null;
    },
  };
  return store;
};

const queryClient = new QueryClient();
const connect = (store: SessionStore) =>
  new GameClient(
    "ws://test/ws",
    store,
    queryClient,
    () => new FakeSocket() as unknown as WebSocket,
  );
const room = () =>
  queryClient.getQueryData<{ code: string; version: number }>(roomKey);

const last = FakeSocket.last;
// What the server sends because of someone else: no id.
const withVersion = (version: number) => ({
  type: "roomUpdated",
  room: { ...joined.room, version },
});
const seated = async (client: GameClient) => {
  const created = client.request({ type: "createRoom", name: "Ana" });
  last().accept();
  last().answer(joined);
  await created;
};

beforeEach(() => {
  vi.useFakeTimers();
  FakeSocket.all = [];
  queryClient.clear();
});
afterEach(() => vi.useRealTimers());

describe("GameClient", () => {
  it("connects on the first message and sends it once open", () => {
    const client = connect(memoryStore());
    client.send({ type: "createRoom", name: "Ana" });
    expect(client.getSnapshot().connection).toBe("connecting");
    last().accept();
    expect(last().sent).toEqual([{ type: "createRoom", name: "Ana" }]);
  });

  it("remembers the seat when the server confirms the join", () => {
    const store = memoryStore();
    const client = connect(store);
    client.send({ type: "createRoom", name: "Ana" });
    last().accept();
    last().receive(joined);
    expect(store.load()).toEqual({
      code: joined.room.code,
      token: joined.token,
    });
    expect(room()?.code).toBe(joined.room.code);
  });

  it("drops a view older than the one it shows", () => {
    const client = connect(memoryStore());
    client.send({ type: "createRoom", name: "Ana" });
    last().accept();
    last().receive(withVersion(4));
    last().receive(withVersion(3));
    expect(room()?.version).toBe(4);
  });

  it("keeps the room and reports a message that breaks the contract", () => {
    const client = connect(memoryStore());
    client.send({ type: "createRoom", name: "Ana" });
    last().accept();
    last().receive(lookout);
    vi.spyOn(console, "error").mockImplementation(() => {});
    last().receive({ type: "roomUpdated", room: { code: "X" } });
    last().onmessage?.({ data: "not json" });
    expect(room()?.code).toBe(lookout.room.code);
    expect(client.getSnapshot().error).toBe(
      "unreadable message from the server",
    );
  });

  it("rejoins with the stored seat after a reload", () => {
    const client = connect(memoryStore({ code: "ABCDE", token: "t" }));
    client.start();
    last().accept();
    expect(last().sent).toEqual([
      { type: "rejoin", code: "ABCDE", token: "t" },
    ]);
  });

  it("forgets a seat the server no longer knows", () => {
    const store = memoryStore({ code: "ABCDE", token: "t" });
    const client = connect(store);
    client.start();
    last().accept();
    last().answer({ type: "error", reason: "no such room" });
    expect(store.load()).toBeNull();
    expect(room()).toBeUndefined();
    expect(client.getSnapshot()).toEqual({
      connection: "idle",
      error: "no such room",
    });
  });

  it("reconnects with a growing delay and rejoins", () => {
    const client = connect(memoryStore({ code: "ABCDE", token: "t" }));
    client.start();
    last().accept();
    last().drop();
    expect(client.getSnapshot().connection).toBe("reconnecting");
    vi.advanceTimersByTime(499);
    expect(FakeSocket.all).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.all).toHaveLength(2);
    last().drop();
    vi.advanceTimersByTime(999);
    expect(FakeSocket.all).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.all).toHaveLength(3);
    last().accept();
    expect(last().sent).toEqual([
      { type: "rejoin", code: "ABCDE", token: "t" },
    ]);
    expect(client.getSnapshot().connection).toBe("open");
  });

  it("does not fight another tab for the seat", () => {
    const client = connect(memoryStore({ code: "ABCDE", token: "t" }));
    client.start();
    last().accept();
    last().drop("replaced by a newer connection");
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(1);
    expect(client.getSnapshot().connection).toBe("replaced");
    client.takeOver();
    expect(FakeSocket.all).toHaveLength(2);
  });

  it("leaves for good", () => {
    const store = memoryStore();
    const client = connect(store);
    client.send({ type: "createRoom", name: "Ana" });
    last().accept();
    last().receive(joined);
    client.leave();
    last().drop();
    vi.advanceTimersByTime(60_000);
    expect(store.load()).toBeNull();
    expect(FakeSocket.all).toHaveLength(1);
    expect(room()).toBeUndefined();
    expect(client.getSnapshot()).toEqual({ connection: "idle", error: null });
  });

  it("tells the server when it leaves", () => {
    const client = connect(memoryStore());
    client.send({ type: "createRoom", name: "Ana" });
    last().accept();
    last().receive(joined);
    const socket = last();
    client.leave();
    expect(socket.sent.at(-1)).toEqual({ type: "leave" });
  });

  it("settles a command with the answer of the server", async () => {
    const client = connect(memoryStore());
    const created = client.request({ type: "createRoom", name: "Ana" });
    last().accept();
    last().answer(joined);
    await expect(created).resolves.toMatchObject({ code: joined.room.code });
  });

  it("matches each of two commands in flight with its own answer", async () => {
    const client = connect(memoryStore());
    await seated(client);
    let firstSettled = false;
    const first = client
      .request({ type: "joinTeam", team: "BABORD" })
      .then((room) => {
        firstSettled = true;
        return room;
      });
    const second = client.request({ type: "reveal", index: 3 });
    const [, idOfFirst, idOfSecond] = last().sent.map((message) => message.id);
    expect(idOfFirst).not.toBe(idOfSecond);

    last().answer(withVersion(5), 2);
    await expect(second).resolves.toMatchObject({ version: 5 });
    expect(firstSettled).toBe(false);
    last().answer(withVersion(6), 1);
    await expect(first).resolves.toMatchObject({ version: 6 });
  });

  it("does not settle a command with an update caused by someone else", async () => {
    const client = connect(memoryStore());
    await seated(client);
    let settled = false;
    const reveal = client.request({ type: "reveal", index: 3 }).then(() => {
      settled = true;
    });

    last().receive(withVersion(5));
    await vi.advanceTimersByTimeAsync(0);
    expect(settled).toBe(false);
    expect(room()?.version).toBe(5);

    last().answer(withVersion(6));
    await reveal;
    expect(settled).toBe(true);
  });

  it("settles nothing with an answer without id, even before any answer carried one", async () => {
    const client = connect(memoryStore());
    let settled = false;
    const created = client
      .request({ type: "createRoom", name: "Ana" })
      .then(() => {
        settled = true;
      });
    last().accept();
    const { id, ...withoutId } = joined;
    last().receive(withoutId);
    await vi.advanceTimersByTimeAsync(0);
    expect(settled).toBe(false);
    expect(room()?.code).toBe(joined.room.code);

    last().answer(joined);
    await created;
    expect(settled).toBe(true);
  });

  it("rejects a command the server refuses, and only that one", async () => {
    const client = connect(memoryStore());
    const refused = client.request({ type: "start" });
    last().accept();
    last().answer({
      type: "error",
      reason: "each team needs at least 2 players",
    });
    await expect(refused).rejects.toThrow("each team needs at least 2 players");
    expect(client.getSnapshot().error).toBeNull();
  });

  it("rejects only the command a refusal answers, a refusal without id none", async () => {
    const client = connect(memoryStore());
    await seated(client);
    let rejected = false;
    const start = client.request({ type: "start" }).catch(() => {
      rejected = true;
    });
    const pass = client.request({ type: "pass" });

    last().answer({ type: "error", reason: "not your turn" }, 2);
    await expect(pass).rejects.toThrow("not your turn");
    last().receive({ type: "error", reason: "unknown player" });
    await vi.advanceTimersByTimeAsync(0);
    expect(rejected).toBe(false);
    expect(client.getSnapshot().error).toBe("unknown player");

    last().answer(withVersion(5), 1);
    await start;
    expect(rejected).toBe(false);
  });

  it("shows a refusal nobody was waiting for", () => {
    const client = connect(memoryStore());
    client.send({ type: "start" });
    last().accept();
    last().receive({ type: "error", reason: "unknown player" });
    expect(client.getSnapshot().error).toBe("unknown player");
  });

  it("watches without remembering a seat, and forgets the room when the connection drops", async () => {
    const store = memoryStore();
    const client = connect(store);
    const watching = client.request({ type: "watch", code: "ABCDE" });
    last().accept();
    expect(last().sent).toEqual([{ type: "watch", id: "1", code: "ABCDE" }]);
    last().answer(spectator);
    await expect(watching).resolves.toMatchObject({ you: null });
    expect(store.load()).toBeNull();
    expect(room()?.code).toBe(spectator.room.code);

    last().drop();
    expect(room()).toBeUndefined();
    expect(client.getSnapshot()).toEqual({
      connection: "idle",
      error: "connection lost",
    });
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.all).toHaveLength(1);
  });

  it("rejects the commands in flight when the connection drops", async () => {
    const client = connect(memoryStore());
    const lost = client.request({ type: "createRoom", name: "Ana" });
    last().accept();
    last().drop();
    await expect(lost).rejects.toThrow("connection lost");
  });
});
