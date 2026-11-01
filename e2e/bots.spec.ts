import { expect, type Page, test } from "@playwright/test";

const heading = (page: Page) => page.getByRole("heading", { level: 1 });

test("one player plays a whole game against bots", async ({ page }) => {
  // Each round trip of a bot is a few hundred milliseconds here (SEMAPHORE_BOTS_THINK and _PAUSE, set in
  // playwright.config.ts), instead of the seconds a person would wait for.
  test.setTimeout(120_000);
  const team = (name: string) =>
    page.getByRole("region", { name: `Équipe ${name}` });
  const level = (name: string) => page.getByRole("button", { name });

  await page.goto("/");
  await page.getByLabel("Ton nom").fill("Alice");
  await page.getByRole("button", { name: "Créer un salon" }).click();
  await expect(heading(page)).toHaveText("Salon");

  await test.step("Alice joins Bâbord, whose Signaleur is an easy bot", async () => {
    await page.getByRole("button", { name: "Rejoindre Bâbord" }).click();
    await level("Facile").click();
    await team("Bâbord")
      .getByRole("button", { name: "Ajouter un bot Signaleur" })
      .click();
    await expect(team("Bâbord")).toContainText(
      "Bot 1 · Signaleur · niveau Facile",
    );
    await expect(team("Bâbord")).toContainText("Alice (toi) · hôte");
  });

  await test.step("Tribord is a hard Signaleur and a lookout the host changes their mind about", async () => {
    await level("Difficile").click();
    await team("Tribord")
      .getByRole("button", { name: "Ajouter un bot Signaleur" })
      .click();
    await team("Tribord")
      .getByRole("button", { name: "Ajouter un bot Veilleur" })
      .click();
    await expect(team("Tribord")).toContainText("Bot 3 · niveau Difficile");

    await page.getByRole("button", { name: "Retirer Bot 3" }).click();
    await expect(team("Tribord")).not.toContainText("Bot 3");
    await level("Normal").click();
    await team("Tribord")
      .getByRole("button", { name: "Ajouter un bot Veilleur" })
      .click();
    await expect(team("Tribord")).toContainText("Bot 3 · niveau Normal");
  });

  await page.getByRole("button", { name: "Lancer la partie" }).click();
  await expect(heading(page)).toHaveText(/^Tour de (Bâbord|Tribord)$/);

  // The bots do everything but Alice's turns: when her team has signalled she passes, and Tribord's bots play
  // their own turns until the game is over, one way or the other.
  const pass = page.getByRole("button", { name: "Passer" });
  const over = heading(page).filter({ hasText: /gagne$/ });
  for (;;) {
    await expect(pass.or(over)).toBeVisible({ timeout: 60_000 });
    if (await over.isVisible()) {
      break;
    }
    await pass.click();
    await expect(pass).toBeHidden();
  }
  await expect(over).toHaveText(/^(Bâbord|Tribord) gagne$/);
});
