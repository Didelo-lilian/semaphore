import { expect, test } from "@playwright/test";

const heading = (page: import("@playwright/test").Page) =>
  page.getByRole("heading", { level: 1 });

test("a guest keeps the pseudo across rooms, tabs and renames, and others see it", async ({
  browser,
}) => {
  const ana = await browser.newContext({
    permissions: ["clipboard-read", "clipboard-write"],
  });
  const first = await ana.newPage();

  await test.step("the first visit asks for a pseudo and the server remembers it", async () => {
    await first.goto("/");
    await expect(first.getByText(/retenu sur cet appareil/)).toBeVisible();
    await first.getByLabel("Ton nom").fill("Ana");
    await first.getByRole("button", { name: "Créer un salon" }).click();
    await expect(heading(first)).toHaveText("Salon");
    await expect(first.getByText("Ana (toi)")).toBeVisible();
    const me = await ana.request.get("/api/me");
    expect(await me.json()).toMatchObject({ kind: "guest", name: "Ana" });
  });

  await test.step("another tab of the browser starts from it and the server names the player", async () => {
    const second = await ana.newPage();
    await second.goto("/");
    await expect(second.getByLabel("Ton nom")).toHaveValue("Ana");
    await second.getByRole("button", { name: "Créer un salon" }).click();
    await expect(heading(second)).toHaveText("Salon");
    await expect(second.getByText("Ana (toi)")).toBeVisible();
    await second.close();
  });

  await test.step("renaming is remembered too", async () => {
    const third = await ana.newPage();
    await third.goto("/");
    await third.getByLabel("Ton nom").fill("Anna");
    await third.getByRole("button", { name: "Créer un salon" }).click();
    await expect(third.getByText("Anna (toi)")).toBeVisible();
    const me = await ana.request.get("/api/me");
    expect(await me.json()).toMatchObject({ kind: "guest", name: "Anna" });
    await third.close();
  });

  await test.step("a second guest joins the first room by its link and both read each other", async () => {
    await first
      .getByRole("button", { name: "Copier le lien d'invitation" })
      .click();
    await expect(
      first.getByRole("button", { name: "Lien copié" }),
    ).toBeVisible();
    await first.bringToFront();
    const link = await first.evaluate(() => navigator.clipboard.readText());

    const ben = await (await browser.newContext()).newPage();
    await ben.goto(link);
    await ben.getByLabel("Ton nom").fill("Ben");
    await ben.getByRole("button", { name: "Rejoindre le salon" }).click();
    await expect(heading(ben)).toHaveText("Salon");
    await expect(ben.getByText("Ben (toi)")).toBeVisible();
    await expect(ben.getByText(/^Ana/)).toBeVisible();
    await expect(first.getByText("Ben", { exact: true })).toBeVisible();
  });
});

test("the name is asked in each room when the server cannot say who the player is", async ({
  page,
}) => {
  await page.route("**/api/me", (route) =>
    route.fulfill({
      status: 404,
      contentType: "text/html",
      body: "<!doctype html>",
    }),
  );
  await page.goto("/");
  await expect(page.getByLabel("Ton nom")).toHaveValue("");
  await expect(page.getByText(/retenu sur cet appareil/)).toHaveCount(0);
  await page.getByLabel("Ton nom").fill("Ana");
  await page.getByRole("button", { name: "Créer un salon" }).click();
  await expect(heading(page)).toHaveText("Salon");
  await expect(page.getByText("Ana (toi)")).toBeVisible();
});
