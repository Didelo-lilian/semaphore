import { afterEach, describe, expect, it, vi } from "vitest";
import { fetchMe, saveName } from "./me.ts";

const answer = (status: number, body: unknown) =>
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => new Response(JSON.stringify(body), { status })),
  );

afterEach(() => {
  vi.unstubAllGlobals();
  Reflect.deleteProperty(document, "cookie");
});

describe("fetchMe", () => {
  it("reads a player who signed in, with the providers the server lists", async () => {
    answer(200, {
      kind: "account",
      id: "a1",
      name: "Ana",
      providers: ["github"],
    });
    expect(await fetchMe()).toEqual({ kind: "account", id: "a1", name: "Ana" });
    answer(200, { kind: "anonymous", providers: ["github", "google"] });
    expect(await fetchMe()).toEqual({ kind: "anonymous" });
  });

  it("reads an anonymous visitor and a guest", async () => {
    answer(200, { kind: "anonymous" });
    expect(await fetchMe()).toEqual({ kind: "anonymous" });
    answer(200, { kind: "guest", id: "g1", name: "Ana" });
    expect(await fetchMe()).toEqual({ kind: "guest", id: "g1", name: "Ana" });
  });

  it.each([404, 429, 503])("cannot say who you are on a %i", async (status) => {
    answer(status, { error: "no" });
    expect(await fetchMe()).toBeNull();
  });

  it("cannot say who you are when the edge answers with the page", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response("<!doctype html>", { status: 200 })),
    );
    expect(await fetchMe()).toBeNull();
  });

  it("cannot say who you are without a network", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("offline");
      }),
    );
    expect(await fetchMe()).toBeNull();
  });
});

describe("saveName", () => {
  it("puts the name with the CSRF token of the cookie", async () => {
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get: () => "other=1; __Host-XSRF-TOKEN=tok",
    });
    answer(200, { kind: "guest", id: "g1", name: "Ana" });
    expect(await saveName("Ana")).toEqual({
      kind: "guest",
      id: "g1",
      name: "Ana",
    });
    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/me");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"name":"Ana"}');
    expect(init.headers).toMatchObject({ "X-XSRF-TOKEN": "tok" });
  });

  it("throws when the server refuses", async () => {
    answer(429, { error: "slow down" });
    await expect(saveName("Ana")).rejects.toThrow("429");
  });
});
