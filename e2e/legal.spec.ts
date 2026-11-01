import { expect, test } from "@playwright/test";

// A phone's width: the pages are meant to be read there first.
test.use({ viewport: { width: 375, height: 812 } });

test("the home page links to the privacy page and the terms", async ({
  page,
}) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "Comment on joue" }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Confidentialité" }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(
    page.getByRole("heading", { level: 1, name: "Confidentialité" }),
  ).toBeVisible();

  await page.getByRole("link", { name: "Conditions d'utilisation" }).click();
  await expect(page).toHaveURL(/\/terms$/);
  await expect(
    page.getByRole("heading", { level: 1, name: "Conditions d'utilisation" }),
  ).toBeVisible();
});

for (const path of ["/privacy", "/terms"]) {
  test(`${path} answers on its own address and fits a phone`, async ({
    page,
  }) => {
    const response = await page.goto(path);
    expect(response?.status()).toBe(200);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth - window.innerWidth,
    );
    expect(overflow).toBeLessThanOrEqual(0);
  });
}

test("the logo and the favicon are served as images", async ({ request }) => {
  for (const [path, type] of [
    ["/logo.svg", "image/svg+xml"],
    ["/logo-120.png", "image/png"],
    ["/favicon.ico", /image\/(x-icon|vnd\.microsoft\.icon)/],
    ["/apple-touch-icon.png", "image/png"],
  ] as const) {
    const response = await request.get(path);
    expect(response.status(), path).toBe(200);
    expect(response.headers()["content-type"], path).toMatch(type);
  }
});
