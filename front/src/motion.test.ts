import { readFileSync } from "node:fs";
import { join } from "node:path";
import { theme } from "@didelo/design-system";
import { describe, expect, it } from "vitest";

// Not `?raw`: Vitest serves a stylesheet it does not process as an empty string.
const css = readFileSync(join(process.cwd(), "src/game.css"), "utf8").replace(
  /\/\*[\s\S]*?\*\//g,
  "",
);
const { duration, easing } = theme.transitions;

const reducedBlock = (() => {
  const start = css.indexOf("@media (prefers-reduced-motion: reduce)");
  let depth = 0;
  for (let end = css.indexOf("{", start); end < css.length; end++) {
    depth += css[end] === "{" ? 1 : css[end] === "}" ? -1 : 0;
    if (depth === 0) {
      return css.slice(start, end + 1);
    }
  }
  throw new Error("no reduced-motion block in game.css");
})();

const rules = (source: string) =>
  [...source.matchAll(/([^{}]+)\{([^{}]*)\}/g)].flatMap(([, selectors, body]) =>
    (selectors ?? "")
      .split(",")
      .map((selector) => ({ selector: selector.trim(), body: body ?? "" })),
  );

describe("the motion of game.css", () => {
  it("takes its durations and easings from the theme of the design system", () => {
    const token = (name: string) =>
      css.match(new RegExp(`--sem-${name}:\\s*([^;]+);`))?.[1];
    expect(token("duration-turn")).toBe(`${duration.complex}ms`);
    expect(token("duration-enter")).toBe(`${duration.enteringScreen}ms`);
    expect(token("duration-leave")).toBe(`${duration.leavingScreen}ms`);
    // The stylesheet formatter writes 0.0 as 0, so the curves are compared by their numbers.
    const curve = (value = "") => value.match(/[\d.]+/g)?.map(Number);
    expect(curve(token("ease-in-out"))).toEqual(curve(easing.easeInOut));
    expect(curve(token("ease-out"))).toEqual(curve(easing.easeOut));
    expect(curve(token("ease-in"))).toEqual(curve(easing.easeIn));
  });

  it("turns every animation and transition off under prefers-reduced-motion", () => {
    const off = rules(reducedBlock);
    const moving = rules(css.replace(reducedBlock, "")).filter(({ body }) =>
      /\b(animation|transition)\s*:/.test(body),
    );
    expect(moving.length).toBeGreaterThan(0);
    for (const { selector, body } of moving) {
      const property = /\banimation\s*:/.test(body)
        ? "animation"
        : "transition";
      expect(
        off.some(
          (rule) =>
            rule.selector === selector &&
            new RegExp(`${property}:\\s*none|display:\\s*none`).test(rule.body),
        ),
        `${selector} (${property})`,
      ).toBe(true);
    }
  });
});
