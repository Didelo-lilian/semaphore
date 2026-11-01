import {
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { z } from "zod";

// The sign-in providers that are on: the server lists them to whoever is not signed in (ADR 0011).
const providers = z.array(z.string()).default([]);

const meSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("anonymous"), providers }),
  z.object({
    kind: z.literal("guest"),
    id: z.string(),
    name: z.string(),
    providers,
  }),
  z.object({ kind: z.literal("account"), id: z.string(), name: z.string() }),
]);

/** Who the server says this browser is, outside any room (ADR 0010). */
export type Me = z.infer<typeof meSchema>;

export const meKey = ["me"] as const;

const TOKEN_COOKIE = "__Host-XSRF-TOKEN";

// A server that hangs must not keep the player out of a room.
const TIMEOUT_MS = 5_000;

function timeout() {
  const controller = new AbortController();
  setTimeout(() => controller.abort(), TIMEOUT_MS);
  return controller.signal;
}

// GET /api/me hands the cookie out; a browser that refused it (Safari over plain HTTP) has none.
const csrfToken = () =>
  document.cookie
    .split("; ")
    .find((cookie) => cookie.startsWith(`${TOKEN_COOKIE}=`))
    ?.slice(TOKEN_COOKIE.length + 1);

/**
 * Who the server says this browser is, or null when it cannot say: no route yet (the edge or an
 * older backend answers with the page or a 404), too many requests, no network. The game then asks
 * for the name in each room, as it did before guests.
 */
export async function fetchMe(): Promise<Me | null> {
  try {
    const response = await fetch("/api/me", {
      headers: { Accept: "application/json" },
      signal: timeout(),
    });
    return response.ok ? meSchema.parse(await response.json()) : null;
  } catch {
    return null;
  }
}

async function send(method: string, url: string, body?: unknown) {
  const token = csrfToken();
  const response = await fetch(url, {
    method,
    headers: {
      Accept: "application/json",
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token === undefined ? {} : { "X-XSRF-TOKEN": token }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: timeout(),
  });
  if (!response.ok) {
    throw new Error(`${method} ${url} answered ${response.status}`);
  }
  return response;
}

/** Remembers the pseudo: makes the guest or renames it. Throws when the server refuses. */
export async function saveName(name: string): Promise<Me> {
  const response = await send("PUT", "/api/me", { name });
  return meSchema.parse(await response.json());
}

/** Ends the session: the browser is nobody again. Throws when the server refuses. */
export const signOut = () => send("POST", "/api/auth/logout");

/** Deletes the account and ends the session (ADR 0011). Throws when the server refuses. */
export const deleteAccount = () => send("DELETE", "/api/auth/account");

/** The query never fails: what cannot be known is null. */
export const meOptions = queryOptions({
  queryKey: meKey,
  queryFn: fetchMe,
  staleTime: Number.POSITIVE_INFINITY,
  retry: false,
});

export const useMe = () => useQuery(meOptions);

export const useSaveName = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: saveName,
    onSuccess: (me) => queryClient.setQueryData(meKey, me),
  });
};

// The session is gone: what the server says about this browser has to be read again.
const useEndSession = (end: () => Promise<unknown>) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: end,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meKey }),
  });
};

export const useSignOut = () => useEndSession(signOut);

export const useDeleteAccount = () => useEndSession(deleteAccount);
