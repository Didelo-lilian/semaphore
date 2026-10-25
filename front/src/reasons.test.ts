import { describe, expect, it } from "vitest";
import { explain } from "./reasons.ts";

describe("explain", () => {
  it("translates what the server says when it closes a connection that asked for too many unknown rooms", () => {
    expect(explain("too many unknown rooms")).toBe(
      "Trop de codes inconnus : la connexion a été coupée. Vérifie le code, puis réessaie.",
    );
  });

  it("translates what the server says when Redis cannot take a change", () => {
    expect(explain("the server is busy, try again")).toBe(
      "Le serveur est occupé, réessaie dans un instant.",
    );
    expect(explain("the server is full, try again later")).toBe(
      "Le serveur est plein, réessaie plus tard.",
    );
  });

  it("translates the refusals that carry a number", () => {
    expect(explain("each team needs at least 2 players")).toBe(
      "Chaque équipe a besoin d'au moins 2 joueurs.",
    );
    expect(explain("the number is from 0 to 9")).toBe("Le nombre va de 0 à 9.");
  });

  it("shows a reason it does not know as it is", () => {
    expect(explain("something new")).toBe("something new");
  });
});
