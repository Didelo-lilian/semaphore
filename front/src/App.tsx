import { Alert, Button } from "@didelo/design-system";
import {
  getRouteApi,
  Navigate,
  Outlet,
  useNavigate,
} from "@tanstack/react-router";
import { useEffect } from "react";
import { useCommand, useGame, useRoom } from "./game.tsx";
import type { Team } from "./protocol.ts";
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

export const HomePage = () => {
  const { snapshot } = useGame();
  const { room } = homeApi.useSearch();
  const navigate = useNavigate();
  const enter = useCommand((joined) =>
    navigate({ to: "/salon/$code", params: { code: joined.code } }),
  );
  return (
    <Home
      invited={room ?? null}
      busy={enter.isPending}
      error={enter.error?.message ?? snapshot.error}
      onCreate={(name) => enter.mutate({ type: "createRoom", name })}
      onJoin={(code, name) => enter.mutate({ type: "joinRoom", code, name })}
    />
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
