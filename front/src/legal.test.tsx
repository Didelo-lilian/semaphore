import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { GameClient } from "./client.ts";
import { FakeSocket } from "./fake-socket.ts";
import { GameProvider } from "./game.tsx";
import { createAppRouter } from "./router.tsx";

const mount = async (path: string) => {
  const queryClient = new QueryClient();
  const client = new GameClient(
    "ws://test/ws",
    { load: () => null, save: () => {}, clear: () => {} },
    queryClient,
    () => new FakeSocket() as unknown as WebSocket,
  );
  client.start();
  const router = createAppRouter();
  await router.navigate({ to: path });
  render(
    <QueryClientProvider client={queryClient}>
      <GameProvider client={client}>
        <RouterProvider router={router} />
      </GameProvider>
    </QueryClientProvider>,
  );
  return router;
};

describe("the legal pages", () => {
  it("describes the game on the home page and links to both pages", async () => {
    await mount("/");
    expect(
      await screen.findByRole("heading", { name: "Comment on joue" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Confidentialité" }),
    ).toHaveAttribute("href", "/privacy");
    expect(
      screen.getByRole("link", { name: "Conditions d'utilisation" }),
    ).toHaveAttribute("href", "/terms");
  });

  it("opens the privacy page from the home page and goes on to the terms", async () => {
    const router = await mount("/");
    await userEvent.click(
      await screen.findByRole("link", { name: "Confidentialité" }),
    );
    expect(
      await screen.findByRole("heading", { level: 1, name: "Confidentialité" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/privacy");

    await userEvent.click(
      screen.getByRole("link", { name: "Conditions d'utilisation" }),
    );
    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Conditions d'utilisation",
      }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/terms");
  });

  it("opens straight on a page, as a link from Google's consent screen does", async () => {
    await mount("/terms");
    expect(
      await screen.findByRole("heading", {
        level: 1,
        name: "Conditions d'utilisation",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Retour à l'accueil" }),
    ).toHaveAttribute("href", "/");
  });

  it("says what the game keeps and offers a way to ask", async () => {
    await mount("/privacy");
    for (const heading of [
      "Qui est responsable",
      "Ce que le jeu garde",
      "Tes droits",
    ]) {
      expect(
        await screen.findByRole("heading", { name: heading }),
      ).toBeInTheDocument();
    }
    expect(
      screen.getByRole("link", { name: /page des demandes du dépôt/ }),
    ).toHaveAttribute(
      "href",
      "https://github.com/Didelo-lilian/semaphore/issues",
    );
  });
});
