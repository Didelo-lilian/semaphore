import { expect, test } from "@playwright/test";
import pg from "pg";
import { POSTGRES } from "./database";
import {
  type Crew,
  heading,
  invite,
  type Player,
  playUntilWon,
  startGame,
  type Team,
} from "./game";

// How the database names the winning team.
const WINNER: Record<Team, string> = { Bâbord: "BABORD", Tribord: "TRIBORD" };

test("a player signed in with GitHub plays with three guests, and the game is recorded with the account", async ({
  browser,
}) => {
  const db = new pg.Client(POSTGRES);
  await db.connect();
  try {
    const rows = async (sql: string, ...params: unknown[]) =>
      (await db.query(sql, params)).rows;
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
    const signIn = async () => {
      await alice.page.getByLabel("Ton nom").fill(alice.name);
      await alice.page
        .getByRole("button", { name: "Se connecter avec GitHub" })
        .click();
      await expect(alice.page.getByText(/Connecté en tant que/)).toContainText(
        alice.name,
      );
    };
    const githubAccounts = () =>
      rows("SELECT id FROM account WHERE provider = 'github'");
    // The games are dated by the clock of the database, not the one of this machine.
    const [{ since }] = await rows("SELECT now() AS since");

    await test.step("Alice signs in with the provider and the server says who she is", async () => {
      await alice.page.goto("/");
      await signIn();
      const me = await alice.page.request.get("/api/me");
      expect(await me.json()).toMatchObject({
        kind: "account",
        name: alice.name,
      });
      expect(await githubAccounts()).toHaveLength(1);
    });

    await test.step("she signs out, signs in again, and it is the same account", async () => {
      await alice.page.getByRole("button", { name: "Se déconnecter" }).click();
      await expect(
        alice.page.getByRole("button", { name: "Se connecter avec GitHub" }),
      ).toBeVisible();
      await signIn();
      expect(await githubAccounts()).toHaveLength(1);
    });

    const [{ id: accountId }] = await githubAccounts();
    const recorded = () =>
      rows(
        `SELECT id, winner, end_reason FROM game
         WHERE ended_at >= $1 AND EXISTS (
           SELECT 1 FROM game_player WHERE game_id = game.id AND account_id = $2)`,
        since,
        accountId,
      );

    await test.step("she opens a room, three guests join it, and they play to the end", async () => {
      await alice.page.getByRole("button", { name: "Créer un salon" }).click();
      await expect(heading(alice.page)).toHaveText("Salon");
      await invite(alice, [bob, carol, dave]);
      await startGame(alice, [
        [dave, "Bâbord"],
        [carol, "Tribord"],
        [bob, "Tribord"],
        [alice, "Bâbord"],
      ]);
      const crews: Record<Team, Crew> = {
        Bâbord: { signaler: alice, lookout: dave },
        Tribord: { signaler: bob, lookout: carol },
      };
      const won = await playUntilWon(crews, alice.page);
      await expect(heading(alice.page)).toHaveText(`${won} gagne`);

      // The game is written when it ends, off the players' path: it may come a moment after the screen says so.
      await expect.poll(async () => (await recorded()).length).toBe(1);
      const [game] = await recorded();
      expect(game).toMatchObject({
        winner: WINNER[won],
        end_reason: "COMPLETE",
      });
      expect(
        await rows(
          "SELECT name, team, role, account_id FROM game_player WHERE game_id = $1 ORDER BY name",
          game.id,
        ),
      ).toEqual([
        {
          name: "Alice",
          team: "BABORD",
          role: "SIGNALER",
          account_id: accountId,
        },
        { name: "Bob", team: "TRIBORD", role: "SIGNALER", account_id: null },
        { name: "Carol", team: "TRIBORD", role: "LOOKOUT", account_id: null },
        { name: "Dave", team: "BABORD", role: "LOOKOUT", account_id: null },
      ]);
    });

    await test.step("she deletes her account: the row goes, and so does the game, which had no other account", async () => {
      await alice.page
        .getByRole("button", { name: "Retour à l'accueil" })
        .click();
      await alice.page
        .getByRole("button", { name: "Supprimer mon compte" })
        .click();
      await alice.page
        .getByRole("dialog")
        .getByRole("button", { name: "Supprimer" })
        .click();
      await expect(
        alice.page.getByRole("button", { name: "Se connecter avec GitHub" }),
      ).toBeVisible();
      expect(await githubAccounts()).toHaveLength(0);
      expect(await recorded()).toHaveLength(0);
    });
  } finally {
    await db.end();
  }
});
