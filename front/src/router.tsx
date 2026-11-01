import {
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router";
import { z } from "zod";
import { HomePage, SalonPage, Shell } from "./App.tsx";
import { PrivacyPage, TermsPage } from "./ui/Legal.tsx";

const rootRoute = createRootRoute({ component: Shell });

// `?room=CODE` is the invitation link: a code that is not one is just ignored. `?login=` is where the
// sign-in sends the browser back to when it stops (ADR 0011), and `provider` the one it was for.
const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  validateSearch: z.object({
    room: z
      .string()
      .transform((code) => code.toUpperCase())
      .optional()
      .catch(undefined),
    login: z.enum(["failed", "pseudo"]).optional().catch(undefined),
    provider: z.string().optional().catch(undefined),
  }),
  component: HomePage,
});

const salonRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/salon/$code",
  component: SalonPage,
});

const privacyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/privacy",
  component: PrivacyPage,
});

const termsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/terms",
  component: TermsPage,
});

export const createAppRouter = () =>
  createRouter({
    routeTree: rootRoute.addChildren([
      homeRoute,
      salonRoute,
      privacyRoute,
      termsRoute,
    ]),
  });

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
