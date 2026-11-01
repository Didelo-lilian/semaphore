import type { Level } from "../protocol.ts";

export const LEVELS: Level[] = ["EASY", "NORMAL", "HARD"];

export const LEVEL_NAME: Record<Level, string> = {
  EASY: "Facile",
  NORMAL: "Normal",
  HARD: "Difficile",
};
