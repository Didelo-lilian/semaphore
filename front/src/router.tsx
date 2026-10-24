import {
  createRootRoute,
  createRoute,
  createRouter,
} from "@tanstack/react-router";
import { z } from "zod";
import { HomePage, SalonPage, Shell } from "./App.tsx";

const rootRoute = createRootRoute({ component: Shell });

// `?room=CODE` is the invitation link: a code that is not one is just ignored.
const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  validateSearch: z.object({
    room: z
      .string()
      .transform((code) => code.toUpperCase())
      .optional()
      .catch(undefined),
  }),
  component: HomePage,
});

const salonRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/salon/$code",
  component: SalonPage,
});

export const createAppRouter = () =>
  createRouter({ routeTree: rootRoute.addChildren([homeRoute, salonRoute]) });

declare module "@tanstack/react-router" {
  interface Register {
    router: ReturnType<typeof createAppRouter>;
  }
}
