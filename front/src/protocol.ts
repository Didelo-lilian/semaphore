import { z } from "zod";

/** The messages of the WebSocket, as the backend writes them (ADR 0003). */

export const teamSchema = z.enum(["BABORD", "TRIBORD"]);
export type Team = z.infer<typeof teamSchema>;

export const phaseSchema = z.enum(["LOBBY", "SIGNAL", "GUESS", "ENDED"]);
export type Phase = z.infer<typeof phaseSchema>;

export const cardKindSchema = z.enum(["BABORD", "TRIBORD", "CALM", "REEF"]);
export type CardKind = z.infer<typeof cardKindSchema>;

const playerSchema = z.object({
  id: z.string(),
  name: z.string(),
  team: teamSchema.nullable(),
  connected: z.boolean(),
  host: z.boolean(),
  signaler: z.boolean(),
});
export type Player = z.infer<typeof playerSchema>;

const cardSchema = z.object({
  word: z.string(),
  revealed: z.boolean(),
  // Null for a hidden card the viewer has no key for.
  kind: cardKindSchema.nullable(),
});
export type Card = z.infer<typeof cardSchema>;

export const roomViewSchema = z.object({
  code: z.string(),
  // Grows with every change: a view older than the one shown is dropped.
  version: z.number().int(),
  phase: phaseSchema,
  turn: teamSchema.nullable(),
  signal: z.object({ word: z.string(), number: z.number().int() }).nullable(),
  revealsLeft: z.number().int(),
  flags: z.object({ BABORD: z.number().int(), TRIBORD: z.number().int() }),
  winner: teamSchema.nullable(),
  players: z.array(playerSchema),
  board: z.array(cardSchema),
  you: z.string(),
});
export type RoomView = z.infer<typeof roomViewSchema>;

export const serverMessageSchema = z.discriminatedUnion("type", [
  z.object({
    type: z.literal("joined"),
    playerId: z.string(),
    token: z.string(),
    room: roomViewSchema,
  }),
  z.object({ type: z.literal("roomUpdated"), room: roomViewSchema }),
  z.object({ type: z.literal("error"), reason: z.string() }),
]);
export type ServerMessage = z.infer<typeof serverMessageSchema>;

export type ClientMessage =
  | { type: "createRoom"; name: string }
  | { type: "joinRoom"; code: string; name: string }
  | { type: "rejoin"; code: string; token: string }
  | { type: "joinTeam"; team: Team }
  | { type: "start" }
  | { type: "signal"; word: string; number: number }
  | { type: "reveal"; index: number }
  | { type: "pass" };
