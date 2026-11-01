import { Alert, Button } from "@didelo/design-system";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import {
  getRouteApi,
  Navigate,
  Outlet,
  useNavigate,
} from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useCommand, useGame, useRoom } from "./game.tsx";
import {
  meOptions,
  Refused,
  useDeleteAccount,
  useMe,
  useSaveName,
  useSignOut,
} from "./me.ts";
import type { ClientMessage, Team } from "./protocol.ts";
import { About } from "./ui/About.tsx";
import { type Failure, providerName } from "./ui/Account.tsx";
import { Home } from "./ui/Home.tsx";
import { Lobby } from "./ui/Lobby.tsx";
import { Table } from "./ui/Table.tsx";

export const Shell = () => {
  const { client, snapshot } = useGame();
  return (
    <main className="sem-shell">
      {snapshot.connection === "reconnecting" && (
        <Alert severity="warning">Connexion perdue, on te reconnecte…</Alert>
      )}
      {snapshot.connection === "replaced" && (
        <Alert
          severity="warning"
          action={
            <Button onClick={() => client.takeOver()}>Reprendre ici</Button>
          }
        >
          Ta place est utilisée dans un autre onglet.
        </Alert>
      )}
      <Outlet />
    </main>
  );
};

const homeApi = getRouteApi("/");

const failureOf = (
  action: Failure["action"],
  error: Error | null,
): Failure | null =>
  error === null
    ? null
    : { action, status: error instanceof Refused ? error.status : null };

export const HomePage = () => {
  const { snapshot } = useGame();
  const { room, login, provider } = homeApi.useSearch();
  const navigate = useNavigate();
  // The sign-in sends the browser back with a code in the address (ADR 0011). It is read once, then the address is
  // cleaned, so that a reload does not bring the message back.
  const [returned] = useState({ login, provider });
  useEffect(() => {
    if (login !== undefined || provider !== undefined) {
      void navigate({
        to: "/",
        search: (previous) => ({ room: previous.room }),
        replace: true,
      });
    }
  }, [login, provider, navigate]);
  const me = useMe();
  const queryClient = useQueryClient();
  const saveName = useSaveName();
  const enter = useCommand((joined) =>
    navigate({ to: "/salon/$code", params: { code: joined.code } }),
  );
  const signOut = useSignOut();
  const deleteAccount = useDeleteAccount();
  const named =
    me.data == null || me.data.kind === "anonymous" ? null : me.data;
  // The one check of the providers: those the server lists and the front can draw.
  const providers = (
    me.data == null || me.data.kind === "account" ? [] : me.data.providers
  ).filter((name) => providerName(name) !== undefined);

  const go = async (name: string, message: ClientMessage) => {
    // Always saved, not only when it differs from what this page read: another tab may have
    // renamed the guest since, and the handshake names the player after the session.
    // A refused PUT (too many, no route yet) is not a lockout: the server then names the player
    // after the message, as it did before guests.
    // shortcut: a guest renamed while the PUT is refused is still named by its session, upgrade
    // when the front can tell the player.
    // After the first answer of /api/me, which hands out the CSRF cookie the PUT needs.
    await queryClient.ensureQueryData(meOptions);
    await saveName.mutateAsync(name).catch(() => undefined);
    enter.mutate(message);
  };

  // The login needs the pseudo in the session: a name the server refused must not start one that
  // would only come back asking for it.
  const signIn = useMutation({
    mutationFn: async ({
      provider,
      name,
    }: {
      provider: string;
      name: string;
    }) => {
      await queryClient.ensureQueryData(meOptions);
      await saveName.mutateAsync(name);
      window.location.assign(`/api/auth/login/${provider}`);
    },
  });

  return (
    <>
      <Home
        invited={room ?? null}
        initialName={named?.name ?? ""}
        remembered={named?.kind === "guest"}
        // A successful sign-in is on its way to the provider: the page must not start a second one.
        busy={
          enter.isPending ||
          saveName.isPending ||
          signIn.isPending ||
          signIn.isSuccess
        }
        error={enter.error?.message ?? snapshot.error}
        signIn={{
          providers,
          returned: returned.login ?? null,
          resume:
            returned.login === "pseudo" &&
            returned.provider !== undefined &&
            providers.includes(returned.provider)
              ? returned.provider
              : null,
          failed: signIn.isError,
          onSignIn: (provider, name) => signIn.mutate({ provider, name }),
        }}
        account={
          me.data?.kind === "account"
            ? {
                name: me.data.name,
                busy: signOut.isPending || deleteAccount.isPending,
                failure:
                  failureOf("signOut", signOut.error) ??
                  failureOf("delete", deleteAccount.error),
                onSignOut: () => signOut.mutate(),
                onDelete: () => deleteAccount.mutate(),
              }
            : null
        }
        onCreate={(name) => go(name, { type: "createRoom", name })}
        onJoin={(code, name) => go(name, { type: "joinRoom", code, name })}
        onWatch={(code) => enter.mutate({ type: "watch", code })}
      />
      <About />
    </>
  );
};

const salonApi = getRouteApi("/salon/$code");

export const SalonPage = () => {
  const { code } = salonApi.useParams();
  const { client, snapshot } = useGame();
  const room = useRoom();
  const navigate = useNavigate();
  const command = useCommand();
  const { reset } = command;
  const version = room?.version;

  // A refusal answers the view it was sent from: once the room moves on, it is stale.
  useEffect(() => {
    if (version !== undefined) {
      reset();
    }
  }, [version, reset]);

  if (room === null) {
    // No seat in this tab: the link is an invitation.
    return snapshot.connection === "idle" ? (
      <Navigate to="/" search={{ room: code }} replace />
    ) : (
      <p className="sem-hint">Connexion au salon…</p>
    );
  }
  if (room.code !== code) {
    return <Navigate to="/salon/$code" params={{ code: room.code }} replace />;
  }

  // Home first: with the seat gone, this page would send the visitor to the invitation to the room just left.
  const leave = async () => {
    await navigate({ to: "/" });
    client.leave();
  };
  const error = command.error?.message ?? snapshot.error;

  return room.phase === "LOBBY" ? (
    <Lobby
      room={room}
      error={error}
      onJoinTeam={(team: Team) => command.mutate({ type: "joinTeam", team })}
      onStart={() => command.mutate({ type: "start" })}
      onLeave={leave}
    />
  ) : (
    <Table
      room={room}
      busy={command.isPending}
      error={error}
      onSignal={(word, number) =>
        command.mutate({ type: "signal", word, number })
      }
      onReveal={(index) => command.mutate({ type: "reveal", index })}
      onPass={() => command.mutate({ type: "pass" })}
      onLeave={leave}
    />
  );
};
