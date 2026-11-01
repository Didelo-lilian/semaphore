import { existsSync, readFileSync, statSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const file = (path: string) => resolve(import.meta.dirname, "..", path);
const pngSize = (path: string) => {
  const bytes = readFileSync(file(path));
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) };
};

describe("the logo and the favicon", () => {
  it("serves every icon that index.html names", () => {
    const html = readFileSync(file("index.html"), "utf8");
    const hrefs = [
      ...html.matchAll(/<link rel="[^"]*icon"[^>]* href="([^"]+)"/g),
    ].map((match) => match[1]);
    expect(hrefs).toEqual([
      "/logo.svg",
      "/favicon-32.png",
      "/favicon.ico",
      "/apple-touch-icon.png",
    ]);
    for (const href of hrefs) {
      expect(existsSync(file(`public${href}`)), href).toBe(true);
    }
  });

  it("has the logo in the size Google's consent screen asks for", () => {
    expect(pngSize("public/logo-120.png")).toEqual({ width: 120, height: 120 });
    expect(statSync(file("public/logo-120.png")).size).toBeLessThan(1_000_000);
    expect(pngSize("public/logo-512.png")).toEqual({ width: 512, height: 512 });
  });

  it("has the other icons in their sizes", () => {
    expect(pngSize("public/favicon-32.png")).toEqual({ width: 32, height: 32 });
    expect(pngSize("public/apple-touch-icon.png")).toEqual({
      width: 180,
      height: 180,
    });
  });
});
