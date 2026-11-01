import { expect, type Page } from "@playwright/test";

export type Team = "Bâbord" | "Tribord";

export interface Player {
  name: string;
  page: Page;
}

export interface Crew {
  signaler: Player;
  lookout: Player;
}

// Valid whatever the random grid: none of the 160 words of words.txt is contained in it, contains it or shares its
// first four letters. A word added to the list that is too close to it makes the test fail on the server's refusal.
const SIGNAL = "voyage";

// The highest number a signal can carry (MAX_NUMBER in Room.java).
const MAX_NUMBER = 4;

export const heading = (page: Page) => page.getByRole("heading", { level: 1 });

async function signal(page: Page, word: string, number: number) {
  await page.getByLabel("Ton signal").fill(word);
  await page.getByRole("button", { name: String(number), exact: true }).click();
  await page.getByRole("button", { name: "Envoyer le signal" }).click();
}

/** The host copies the link of the room they are in, and the guests join it one after the other. */
export async function invite(host: Player, guests: Player[]) {
  await host.page
    .getByRole("button", { name: "Copier le lien d'invitation" })
    .click();
  await expect(
    host.page.getByRole("button", { name: "Lien copié" }),
  ).toBeVisible();
  // The clipboard can only be read by the focused page, which matters when the windows are visible.
  await host.page.bringToFront();
  const link = await host.page.evaluate(() => navigator.clipboard.readText());

  // One after the other: the Signaleur of a team is its first player in the order of arrival in the room.
  for (const guest of guests) {
    await guest.page.goto(link);
    await guest.page.getByLabel("Ton nom").fill(guest.name);
    await guest.page
      .getByRole("button", { name: "Rejoindre le salon" })
      .click();
    await expect(heading(guest.page)).toHaveText("Salon");
  }
}

/** Everybody picks their team in the order given, then the host starts the game. */
export async function startGame(host: Player, picks: [Player, Team][]) {
  for (const [{ page }, team] of picks) {
    await page.getByRole("button", { name: `Rejoindre ${team}` }).click();
    await expect(
      page
        .getByRole("region", { name: `Équipe ${team}` })
        .getByText("✓ Tu es dans cette équipe"),
    ).toBeVisible();
  }
  await host.page.getByRole("button", { name: "Lancer la partie" }).click();
}

/**
 * The team that starts is drawn. The turns alternate from there: the Signaleur signals as many of the team's words
 * as they can (4 at most), the lookouts touch them one by one then pass, until a team has found all its words. The
 * team that started has one word more: it wins, and is returned.
 */
export async function playUntilWon(
  crews: Record<Team, Crew>,
  watcher: Page,
): Promise<Team> {
  await expect(heading(watcher)).toHaveText(/^Tour de (Bâbord|Tribord)$/);
  let team = (await heading(watcher).innerText()).replace(
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
      return first;
    }
    await lookout.page.getByRole("button", { name: "Passer" }).click();
    team = team === "Bâbord" ? "Tribord" : "Bâbord";
    await expect(heading(watcher)).toHaveText(`Tour de ${team}`);
  }
}
