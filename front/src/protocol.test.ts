import { describe, expect, it } from "vitest";
import error from "./fixtures/error.json";
import joined from "./fixtures/joined-lobby.json";
import answer from "./fixtures/room-answer.json";
import lookout from "./fixtures/room-lookout.json";
import signaler from "./fixtures/room-signaler.json";
import waiting from "./fixtures/room-waiting.json";
import { serverMessageSchema } from "./protocol.ts";

// The fixtures are real messages captured from the running backend (the seat token is replaced).
describe("server messages", () => {
  it("reads a lobby join, with the id of the message it answers", () => {
    const message = serverMessageSchema.parse(joined);
    expect(message.type).toBe("joined");
    expect(message.id).toBe(joined.id);
  });

  it("reads the update that answers a command, id included", () => {
    expect(serverMessageSchema.parse(answer).id).toBe(answer.id);
  });

  it("reads a started game as a Signaleur, key included", () => {
    const message = serverMessageSchema.parse(signaler);
    expect(
      message.type === "roomUpdated" && message.room.board[0]?.kind,
    ).not.toBeNull();
  });

  it("reads a started game as a lookout, key hidden, and sent without an id", () => {
    const message = serverMessageSchema.parse(lookout);
    expect(message.id).toBeUndefined();
    expect(
      message.type === "roomUpdated" &&
        message.room.board.every((c) => c.kind === null),
    ).toBe(true);
  });

  it("reads the seconds left before the turn passes, null when nobody away is waited for", () => {
    const message = serverMessageSchema.parse(waiting);
    expect(message.type === "roomUpdated" && message.room.turnPassesIn).toBe(
      60,
    );
    const idle = serverMessageSchema.parse(lookout);
    expect(idle.type === "roomUpdated" && idle.room.turnPassesIn).toBeNull();
  });

  it("reads an error, with the id of the message it refuses", () => {
    expect(serverMessageSchema.parse(error)).toEqual(error);
  });

  it("refuses a message that breaks the contract", () => {
    expect(serverMessageSchema.safeParse({ type: "roomUpdated" }).success).toBe(
      false,
    );
    expect(serverMessageSchema.safeParse({ type: "nope" }).success).toBe(false);
    expect(
      serverMessageSchema.safeParse({
        ...signaler,
        room: { ...signaler.room, phase: "PAUSED" },
      }).success,
    ).toBe(false);
  });
});
