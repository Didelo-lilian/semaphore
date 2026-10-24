import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import lookout from "../fixtures/room-lookout.json";
import signaler from "../fixtures/room-signaler.json";
import type { RoomView } from "../protocol.ts";
import { Home } from "./Home.tsx";
import { Lobby } from "./Lobby.tsx";
import { Table } from "./Table.tsx";

const lobby = (changes: Partial<RoomView>, you = "a"): RoomView => ({
  code: "USQ2H",
  version: 1,
  phase: "LOBBY",
  turn: null,
  signal: null,
  revealsLeft: 0,
  flags: { BABORD: 2, TRIBORD: 2 },
  winner: null,
  players: [],
  board: [],
  you,
  ...changes,
});

const player = (
  id: string,
  team: RoomView["players"][number]["team"],
  host = false,
) => ({
  id,
  name: id,
  team,
  connected: true,
  host,
  signaler: false,
});

describe("Home", () => {
  it("creates a room with the name typed", async () => {
    const onCreate = vi.fn();
    render(
      <Home
        invited={null}
        busy={false}
        error={null}
        onCreate={onCreate}
        onJoin={vi.fn()}
      />,
    );
    const create = screen.getByRole("button", { name: "Créer un salon" });
    expect(create).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Ton nom"), "Ana");
    await userEvent.click(create);
    await waitFor(() => expect(onCreate).toHaveBeenCalledWith("Ana"));
  });

  it("joins by code, upper-cased", async () => {
    const onJoin = vi.fn();
    render(
      <Home
        invited={null}
        busy={false}
        error={null}
        onCreate={vi.fn()}
        onJoin={onJoin}
      />,
    );
    await userEvent.type(screen.getByLabelText("Ton nom"), "Ben");
    await userEvent.type(screen.getByLabelText("Code du salon"), "usq2h");
    await userEvent.click(screen.getByRole("button", { name: "Rejoindre" }));
    await waitFor(() => expect(onJoin).toHaveBeenCalledWith("USQ2H", "Ben"));
  });

  it("joins the room of an invite link without asking for a code", async () => {
    const onJoin = vi.fn();
    render(
      <Home
        invited="USQ2H"
        busy={false}
        error="no such room"
        onCreate={vi.fn()}
        onJoin={onJoin}
      />,
    );
    expect(screen.queryByLabelText("Code du salon")).toBeNull();
    expect(
      screen.getByText("Ce salon n'existe pas, ou plus."),
    ).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText("Ton nom"), "Ben");
    await userEvent.click(
      screen.getByRole("button", { name: "Rejoindre le salon" }),
    );
    await waitFor(() => expect(onJoin).toHaveBeenCalledWith("USQ2H", "Ben"));
  });
});

describe("Lobby", () => {
  const noop = vi.fn();

  it("lets a player pick a team and shows the others", async () => {
    const onJoinTeam = vi.fn();
    const room = lobby(
      { players: [player("a", null, true), player("b", "TRIBORD")] },
      "b",
    );
    render(
      <Lobby
        room={room}
        error={null}
        onJoinTeam={onJoinTeam}
        onStart={noop}
        onLeave={noop}
      />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Rejoindre Bâbord" }),
    );
    expect(onJoinTeam).toHaveBeenCalledWith("BABORD");
    expect(screen.getByText("✓ Tu es dans cette équipe")).toBeInTheDocument();
    expect(
      screen.getByRole("region", { name: "Sans équipe" }),
    ).toHaveTextContent("a · hôte");
    expect(
      screen.getByText("En attente de l'hôte pour lancer la partie…"),
    ).toBeInTheDocument();
  });

  it("starts only when every team has two players and nobody is left on the dock", async () => {
    const onStart = vi.fn();
    const players = [
      player("a", "BABORD", true),
      player("b", "BABORD"),
      player("c", "TRIBORD"),
    ];
    const { rerender } = render(
      <Lobby
        room={lobby({ players })}
        error={null}
        onJoinTeam={noop}
        onStart={onStart}
        onLeave={noop}
      />,
    );
    expect(
      screen.getByRole("button", { name: "Lancer la partie" }),
    ).toBeDisabled();
    rerender(
      <Lobby
        room={lobby({ players: [...players, player("d", "TRIBORD")] })}
        error={null}
        onJoinTeam={noop}
        onStart={onStart}
        onLeave={noop}
      />,
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Lancer la partie" }),
    );
    expect(onStart).toHaveBeenCalledOnce();
  });
});

describe("Table", () => {
  it("shows the key of a hidden word to a Signaleur only", () => {
    const { unmount } = render(
      <Table room={signaler.room as RoomView} onLeave={vi.fn()} />,
    );
    const keyed = screen
      .getAllByRole("listitem")
      .filter(
        (item) =>
          item.classList.contains("sem-word") &&
          item.querySelector(".sem-word-mark"),
      );
    expect(keyed).toHaveLength(24);
    unmount();
    render(<Table room={lookout.room as RoomView} onLeave={vi.fn()} />);
    expect(document.querySelectorAll(".sem-word-mark")).toHaveLength(0);
  });
});
