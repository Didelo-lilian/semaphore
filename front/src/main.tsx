import "@didelo/design-system/fonts.css";
import "@didelo/design-system/tokens.css";
import "./game.css";
import { theme } from "@didelo/design-system";
import { CssBaseline, ThemeProvider } from "@mui/material";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { GameClient } from "./client.ts";
import { GameProvider } from "./game.tsx";
import { createAppRouter } from "./router.tsx";
import { browserSessionStore } from "./session.ts";

const queryClient = new QueryClient();
const scheme = window.location.protocol === "https:" ? "wss" : "ws";
const client = new GameClient(
  `${scheme}://${window.location.host}/ws`,
  browserSessionStore,
  queryClient,
);
client.start();

const root = document.getElementById("root");
if (root === null) {
  throw new Error("index.html has no #root");
}
createRoot(root).render(
  <StrictMode>
    <ThemeProvider theme={theme} defaultMode="dark">
      <CssBaseline />
      <QueryClientProvider client={queryClient}>
        <GameProvider client={client}>
          <RouterProvider router={createAppRouter()} />
        </GameProvider>
      </QueryClientProvider>
    </ThemeProvider>
  </StrictMode>,
);
