import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Vitest has no globals here, so Testing Library cannot register its own cleanup.
afterEach(cleanup);

// jsdom does not implement it and the router calls it on navigation.
window.scrollTo = () => {};

// jsdom does not implement it either, and the Dialog of the design system observes its content with it.
globalThis.ResizeObserver = class {
  observe() {}
  unobserve() {}
  disconnect() {}
};
