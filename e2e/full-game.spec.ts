import { expect, type Page, test } from "@playwright/test";

type Team = "Bâbord" | "Tribord";

interface Player {
  name: string;
  page: Page;
}

// Valid whatever the random grid: none of the 160 words of words.txt is contained in it, contains it or shares its
// first four letters. A word added to the list that is too close to it makes the test fail on the server's refusal.
const SIGNAL = "voyage";

// The highest number a signal can carry (MAX_NUMBER in Room.java).
const MAX_NUMBER = 4;

const heading = (page: Page) => page.getByRole("heading", { level: 1 });

async function signal(page: Page, word: string, number: number) {
  await page.getByLabel("Ton signal").fill(word);
  await page.getByRole("button", { name: String(number), exact: true }).click();
  await page.getByRole("button", { name: "Envoyer le signal" }).click();
}

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
    await alice.page
      .getByRole("button", { name: "Copier le lien d'invitation" })
      .click();
    await expect(
      alice.page.getByRole("button", { name: "Lien copié" }),
    ).toBeVisible();
    // The clipboard can only be read by the focused page, which matters when the four windows are visible.
    await alice.page.bringToFront();
    const link = await alice.page.evaluate(() =>
      navigator.clipboard.readText(),
    );

    // One after the other: the Signaleur of a team is its first player in the order of arrival in the room.
    for (const guest of [bob, carol, dave]) {
      await guest.page.goto(link);
      await guest.page.getByLabel("Ton nom").fill(guest.name);
      await guest.page
        .getByRole("button", { name: "Rejoindre le salon" })
        .click();
      await expect(heading(guest.page)).toHaveText("Salon");
    }
  });

  await test.step("everybody picks a team, Dave first, and Alice starts the game", async () => {
    const picks: [Player, Team][] = [
      [dave, "Bâbord"],
      [carol, "Tribord"],
      [bob, "Tribord"],
      [alice, "Bâbord"],
    ];
    for (const [{ page }, team] of picks) {
      await page.getByRole("button", { name: `Rejoindre ${team}` }).click();
      await expect(
        page
          .getByRole("region", { name: `Équipe ${team}` })
          .getByText("✓ Tu es dans cette équipe"),
      ).toBeVisible();
    }
    await alice.page.getByRole("button", { name: "Lancer la partie" }).click();
  });

  // Dave entered Bâbord first, but Alice arrived in the room first: she is the Signaleur.
  const crews: Record<Team, { signaler: Player; lookout: Player }> = {
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

  // The team that starts is drawn. The turns alternate from there: the Signaleur signals as many of the team's words
  // as they can (4 at most), the lookouts touch them one by one then pass, until a team has found all its words.
  await expect(heading(alice.page)).toHaveText(/^Tour de (Bâbord|Tribord)$/);
  let team = (await heading(alice.page).innerText()).replace(
    "Tour de ",
    "",
  ) as Team;
  const first = team;
  for (let turn = 0; ; turn++) {
    const { signaler, lookout } = crews[team];
    await expect(signaler.page.getByLabel("Ton signal")).toBeVisible();
    const words = (
      await signaler.page
        .getByLabel(new RegExp(`, caché, ${team}$`))
        .evaluateAll((cards) =>
          cards.map((card) => card.getAttribute("aria-label") as string),
        )
    ).map((label) => label.split(",")[0] as string);
    const number = Math.min(MAX_NUMBER, words.length);

    if (turn === 0) {
      // The server judges the signal: a word of the grid is refused, and the front says why.
      await signal(signaler.page, words[0] as string, number);
      await expect(
        signaler.page.getByRole("alert").filter({ hasText: "trop proche" }),
      ).toBeVisible();
    }
    await signal(signaler.page, SIGNAL, number);
    await expect(
      signaler.page.getByText("Tes Veilleurs cherchent…"),
    ).toBeVisible();
    for (const word of words.slice(0, number)) {
      const card = lookout.page.getByRole("button", {
        name: word,
        exact: true,
      });
      await card.click();
      await expect(card).toBeHidden();
    }
    if (number === words.length) {
      break;
    }
    await lookout.page.getByRole("button", { name: "Passer" }).click();
    team = team === "Bâbord" ? "Tribord" : "Bâbord";
    await expect(heading(alice.page)).toHaveText(`Tour de ${team}`);
  }

  await test.step("the winner is announced to everybody and everybody goes home", async () => {
    for (const { page } of [alice, bob, carol, dave]) {
      await expect(heading(page)).toHaveText(`${first} gagne`);
      await page.getByRole("button", { name: "Retour à l'accueil" }).click();
      await expect(heading(page)).toHaveText("Sémaphore");
      await expect(page).toHaveURL("/");
    }
  });
});
