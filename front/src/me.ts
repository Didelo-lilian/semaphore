import {
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { z } from "zod";

const meSchema = z.discriminatedUnion("kind", [
  z.object({ kind: z.literal("anonymous") }),
  z.object({ kind: z.literal("guest"), id: z.string(), name: z.string() }),
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

/** Remembers the pseudo: makes the guest or renames it. Throws when the server refuses. */
export async function saveName(name: string): Promise<Me> {
  const token = csrfToken();
  const response = await fetch("/api/me", {
    method: "PUT",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      ...(token === undefined ? {} : { "X-XSRF-TOKEN": token }),
    },
    body: JSON.stringify({ name }),
    signal: timeout(),
  });
  if (!response.ok) {
    throw new Error(`PUT /api/me answered ${response.status}`);
  }
  return meSchema.parse(await response.json());
}

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
