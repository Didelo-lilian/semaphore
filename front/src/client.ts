import type { QueryClient } from "@tanstack/react-query";
import {
  type ClientMessage,
  type RoomView,
  serverMessageSchema,
} from "./protocol.ts";
import type { SessionStore } from "./session.ts";

export type Connection =
  | "idle"
  | "connecting"
  | "open"
  | "reconnecting"
  | "replaced";

export interface Snapshot {
  connection: Connection;
  /** A refusal nobody was waiting for, or a message of the client's own. */
  error: string | null;
}

/** The room as the server last described it: TanStack Query holds it, the socket writes it. */
export const roomKey = ["room"] as const;

// What the backend says when a newer connection of the same player takes the seat.
const REPLACED = "replaced by a newer connection";
const RETRY_DELAYS_MS = [500, 1_000, 2_000, 4_000, 8_000];

interface Waiting {
  id: string;
  resolve: (room: RoomView) => void;
  reject: (reason: Error) => void;
}

/**
 * One connection to the game server. It validates every message, writes the newest view of the
 * room in the query cache, remembers the seat in the session store and takes it back after a lost
 * connection. `request` turns a command into a promise, for TanStack Query mutations.
 */
export class GameClient {
  private snapshot: Snapshot = { connection: "idle", error: null };
  private readonly listeners = new Set<() => void>();
  private socket: WebSocket | null = null;
  private pending: ClientMessage[] = [];
  private waiting: Waiting[] = [];
  private lastId = 0;
  // compat: whether the server of this connection has shown that it echoes ids.
  private echoes = false;
  private awaitingRejoin = false;
  private attempts = 0;
  private retry: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private readonly url: string,
    private readonly store: SessionStore,
    private readonly queryClient: QueryClient,
    private readonly open: (url: string) => WebSocket = (u) => new WebSocket(u),
  ) {}

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  getSnapshot = () => this.snapshot;

  /** Takes the seat back when this tab was in a room before a reload. */
  start() {
    if (this.store.load() !== null) {
      this.connect("connecting");
    }
  }

  /** Lets this tab play again after another one took its seat. */
  takeOver() {
    this.attempts = 0;
    this.connect("connecting");
  }

  send(message: ClientMessage) {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(JSON.stringify(message));
    } else {
      this.pending.push(message);
      if (this.socket === null) {
        this.connect("connecting");
      }
    }
  }

  /**
   * Sends a command with an id and settles with the answer that carries it: `joined` or an update
   * resolve it, `error` rejects it. What the server sends because of someone else has no id and
   * settles nothing.
   */
  request(message: ClientMessage) {
    return new Promise<RoomView>((resolve, reject) => {
      const id = String(++this.lastId);
      this.waiting.push({ id, resolve, reject });
      this.send({ ...message, id });
    });
  }

  leave() {
    this.store.clear();
    this.pending = [];
    this.waiting = [];
    this.awaitingRejoin = false;
    this.close();
    this.queryClient.removeQueries({ queryKey: roomKey });
    this.update({ connection: "idle", error: null });
  }

  private close() {
    clearTimeout(this.retry);
    const socket = this.socket;
    this.socket = null;
    socket?.close(1000);
  }

  private connect(connection: Connection) {
    clearTimeout(this.retry);
    this.update({ connection });
    this.echoes = false;
    const socket = this.open(this.url);
    this.socket = socket;
    socket.onopen = () => {
      this.attempts = 0;
      this.update({ connection: "open" });
      const session = this.store.load();
      if (session !== null && this.pending.length === 0) {
        this.awaitingRejoin = true;
        // Its id lets the answer show whether the server echoes ids.
        const id = String(++this.lastId);
        socket.send(JSON.stringify({ type: "rejoin", id, ...session }));
      }
      for (const message of this.pending.splice(0)) {
        socket.send(JSON.stringify(message));
      }
    };
    socket.onmessage = (event) => this.receive(String(event.data));
    socket.onclose = (event) => {
      if (this.socket !== socket) {
        return;
      }
      this.socket = null;
      for (const { reject } of this.waiting.splice(0)) {
        reject(new Error("connection lost"));
      }
      if (event.reason === REPLACED) {
        this.update({ connection: "replaced" });
      } else if (this.store.load() !== null) {
        this.update({ connection: "reconnecting" });
        const delay =
          RETRY_DELAYS_MS[Math.min(this.attempts, RETRY_DELAYS_MS.length - 1)];
        this.attempts++;
        this.retry = setTimeout(() => this.connect("reconnecting"), delay);
      } else {
        this.update({ connection: "idle" });
      }
    };
  }

  private receive(raw: string) {
    let message: unknown;
    try {
      message = JSON.parse(raw);
    } catch {
      this.update({ error: "unreadable message from the server" });
      return;
    }
    const parsed = serverMessageSchema.safeParse(message);
    if (!parsed.success) {
      console.error("unexpected message from the server", parsed.error);
      this.update({ error: "unexpected message from the server" });
      return;
    }
    const received = parsed.data;
    switch (received.type) {
      case "joined":
        this.awaitingRejoin = false;
        this.store.save({ code: received.room.code, token: received.token });
        this.show(received.room);
        this.take(received.id)?.resolve(received.room);
        break;
      case "roomUpdated":
        this.show(received.room);
        this.take(received.id)?.resolve(received.room);
        break;
      case "error": {
        if (this.awaitingRejoin) {
          // The seat is gone (room swept, lobby left): there is nothing to come back to.
          this.awaitingRejoin = false;
          this.store.clear();
          this.close();
          this.queryClient.removeQueries({ queryKey: roomKey });
          this.update({ connection: "idle", error: received.reason });
          break;
        }
        const waiting = this.take(received.id);
        if (waiting === undefined) {
          this.update({ error: received.reason });
        } else {
          waiting.reject(new Error(received.reason));
        }
        break;
      }
    }
  }

  /**
   * The command an answer is for. compat: a server from before request ids echoes none, so until
   * one has shown that it does, an answer without id is for the oldest command; remove it once
   * every server echoes.
   */
  private take(id: string | undefined) {
    this.echoes ||= id !== undefined;
    let index = this.waiting.findIndex((waiting) => waiting.id === id);
    if (id === undefined && !this.echoes) {
      index = 0;
    }
    return index < 0 ? undefined : this.waiting.splice(index, 1)[0];
  }

  /** A view older than the one shown arrived late: the newest wins. */
  private show(room: RoomView) {
    const shown = this.queryClient.getQueryData<RoomView>(roomKey);
    if (shown?.code === room.code && room.version < shown.version) {
      return;
    }
    this.queryClient.setQueryData(roomKey, room);
    this.update({ error: null });
  }

  private update(change: Partial<Snapshot>) {
    this.snapshot = { ...this.snapshot, ...change };
    for (const listener of this.listeners) {
      listener();
    }
  }
}
