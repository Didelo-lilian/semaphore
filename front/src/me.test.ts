import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { deleteAccount, fetchMe, Refused, saveName, signOut } from "./me.ts";

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
    expect(await fetchMe()).toEqual({
      kind: "anonymous",
      providers: ["github", "google"],
    });
  });

  it("reads an anonymous visitor and a guest, with no provider when the server lists none", async () => {
    answer(200, { kind: "anonymous" });
    expect(await fetchMe()).toEqual({ kind: "anonymous", providers: [] });
    answer(200, {
      kind: "guest",
      id: "g1",
      name: "Ana",
      providers: ["github"],
    });
    expect(await fetchMe()).toEqual({
      kind: "guest",
      id: "g1",
      name: "Ana",
      providers: ["github"],
    });
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
      providers: [],
    });
    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/me");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"name":"Ana"}');
    expect(init.headers).toMatchObject({ "X-XSRF-TOKEN": "tok" });
  });

  it.each([403, 429])(
    "throws a refusal that carries the status %i",
    async (status) => {
      answer(status, { error: "no" });
      await expect(saveName("Ana")).rejects.toMatchObject({
        constructor: Refused,
        status,
      });
    },
  );

  it("sends no CSRF header when the browser holds no token", async () => {
    answer(200, { kind: "guest", id: "g1", name: "Ana" });
    await saveName("Ana");
    const [, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(init.headers).not.toHaveProperty("X-XSRF-TOKEN");
  });
});

describe("signOut and deleteAccount", () => {
  beforeEach(() => {
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get: () => "__Host-XSRF-TOKEN=tok",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(null, { status: 204 })),
    );
  });

  it("posts the logout with the CSRF token", async () => {
    await signOut();
    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/auth/logout");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
    expect(init.headers).toMatchObject({ "X-XSRF-TOKEN": "tok" });
  });

  it("deletes the account with the CSRF token", async () => {
    await deleteAccount();
    const [url, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/auth/account");
    expect(init.method).toBe("DELETE");
    expect(init.headers).toMatchObject({ "X-XSRF-TOKEN": "tok" });
  });

  it.each([
    ["logout", signOut],
    ["deletion", deleteAccount],
  ])("throws the status when the server refuses the %s", async (_, request) => {
    answer(403, { error: "no" });
    await expect(request()).rejects.toMatchObject({
      constructor: Refused,
      status: 403,
    });
  });

  it.each([
    ["logout", signOut],
    ["deletion", deleteAccount],
  ])(
    "throws something else than a refusal when the %s gets no answer",
    async (_, request) => {
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => {
          throw new TypeError("offline");
        }),
      );
      const error = await request().catch((caught: unknown) => caught);
      expect(error).toBeInstanceOf(TypeError);
      expect(error).not.toBeInstanceOf(Refused);
    },
  );

  it("sends no CSRF header when the browser holds no token", async () => {
    Object.defineProperty(document, "cookie", {
      configurable: true,
      get: () => "other=1",
    });
    await signOut();
    const [, init] = vi.mocked(fetch).mock.calls[0] as [string, RequestInit];
    expect(init.headers).not.toHaveProperty("X-XSRF-TOKEN");
  });
});
