import { expect, test } from "@playwright/test";
import {
  type Crew,
  heading,
  invite,
  type Player,
  playUntilWon,
  startGame,
  type Team,
} from "./game";

test("four players play a whole game", async ({ browser }) => {
  // A context per player: the seat is kept per tab (sessionStorage) and a context is the closest thing to a person.
  const player = async (name: string): Promise<Player> => {
    const context = await browser.newContext({
      permissions: ["clipboard-read", "clipboard-write"],
    });
    return { name, page: await context.newPage() };
  };
  const alice = await player("Alice");
  const bob = await player("Bob");
  const carol = await player("Carol");
  const dave = await player("Dave");

  await test.step("Alice opens a room and invites the others with its link", async () => {
    await alice.page.goto("/");
    await alice.page.getByLabel("Ton nom").fill(alice.name);
    await alice.page.getByRole("button", { name: "Créer un salon" }).click();
    await expect(heading(alice.page)).toHaveText("Salon");
    await invite(alice, [bob, carol, dave]);
  });

  await test.step("everybody picks a team, Dave first, and Alice starts the game", async () => {
    await startGame(alice, [
      [dave, "Bâbord"],
      [carol, "Tribord"],
      [bob, "Tribord"],
      [alice, "Bâbord"],
    ]);
  });

  // Dave entered Bâbord first, but Alice arrived in the room first: she is the Signaleur.
  const crews: Record<Team, Crew> = {
    Bâbord: { signaler: alice, lookout: dave },
    Tribord: { signaler: bob, lookout: carol },
  };

  await test.step("only the Signaleurs hold the key", async () => {
    await expect(
      alice.page.getByRole("region", { name: "Équipe Bâbord" }),
    ).toContainText("Alice (toi) · Signaleur");
    await expect(
      bob.page.getByRole("region", { name: "Équipe Tribord" }),
    ).toContainText("Bob (toi) · Signaleur");
    const cards = await alice.page
      .getByRole("list", { name: "Grille" })
      .getByRole("listitem")
      .count();
    await expect(alice.page.getByLabel(/, caché, /)).toHaveCount(cards);
    await expect(bob.page.getByLabel(/, caché, /)).toHaveCount(cards);
    await expect(
      carol.page.getByRole("list", { name: "Grille" }),
    ).toBeVisible();
    await expect(carol.page.getByLabel(/, caché, /)).toHaveCount(0);
    await expect(dave.page.getByLabel(/, caché, /)).toHaveCount(0);
  });

  const first = await playUntilWon(crews, alice.page);

  await test.step("the winner is announced to everybody and everybody goes home", async () => {
    for (const { page } of [alice, bob, carol, dave]) {
      await expect(heading(page)).toHaveText(`${first} gagne`);
      await page.getByRole("button", { name: "Retour à l'accueil" }).click();
      await expect(heading(page)).toHaveText("Sémaphore");
      await expect(page).toHaveURL("/");
    }
  });

  await test.step("each of them is a guest the server remembers", async () => {
    for (const { name, page } of [alice, bob, carol, dave]) {
      // A reload: the name must come from the server, not from this page's memory.
      await page.reload();
      await expect(page.getByLabel("Ton nom")).toHaveValue(name);
    }
  });
});
