import { render, screen, waitFor, within } from "@testing-library/react";
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
  // The fixtures are a game just started: Tribord is in play, `signaler` is the Signaleur of Bâbord and `lookout` a
  // lookout of Bâbord. These helpers move the turn to the player's own team and into the phase under test.
  const guessing = (room: RoomView, changes: Partial<RoomView> = {}) => ({
    ...room,
    phase: "GUESS" as const,
    turn: "BABORD" as const,
    signal: { word: "voyage", number: 2 },
    revealsLeft: 3,
    ...changes,
  });

  const handlers = () => ({
    busy: false,
    error: null,
    onSignal: vi.fn(),
    onReveal: vi.fn(),
    onPass: vi.fn(),
    onLeave: vi.fn(),
  });

  it("shows the key of a hidden word to a Signaleur only", () => {
    const { unmount } = render(
      <Table room={signaler.room as RoomView} {...handlers()} />,
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
    render(<Table room={lookout.room as RoomView} {...handlers()} />);
    expect(document.querySelectorAll(".sem-word-mark")).toHaveLength(0);
  });

  it("lets the Signaleur in play send a signal, and nobody else", async () => {
    const props = handlers();
    const room = { ...(signaler.room as RoomView), turn: "BABORD" as const };
    const { unmount } = render(<Table room={room} {...props} />);
    const send = screen.getByRole("button", { name: "Envoyer le signal" });
    expect(send).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Ton signal"), "   ");
    expect(send).toBeDisabled();
    await userEvent.clear(screen.getByLabelText("Ton signal"));
    await userEvent.type(screen.getByLabelText("Ton signal"), "voyage");
    await userEvent.click(screen.getByRole("button", { name: "2" }));
    await userEvent.click(send);
    await waitFor(() =>
      expect(props.onSignal).toHaveBeenCalledWith("voyage", 2),
    );
    unmount();

    render(<Table room={signaler.room as RoomView} {...handlers()} />);
    expect(screen.queryByLabelText("Ton signal")).toBeNull();
    expect(
      screen.getByText("Le Signaleur de Tribord prépare son signal."),
    ).toBeInTheDocument();
  });

  it("leaves the rules of a word to the server", async () => {
    const props = handlers();
    const room = { ...(signaler.room as RoomView), turn: "BABORD" as const };
    render(<Table room={room} {...props} />);
    await userEvent.type(screen.getByLabelText("Ton signal"), "arc-en-ciel");
    await userEvent.click(
      screen.getByRole("button", { name: "Envoyer le signal" }),
    );
    await waitFor(() =>
      expect(props.onSignal).toHaveBeenCalledWith("arc-en-ciel", 1),
    );
  });

  it("shows why the server refused a signal, next to the form", () => {
    const room = { ...(signaler.room as RoomView), turn: "BABORD" as const };
    render(
      <Table
        room={room}
        {...handlers()}
        error="the signal is too close to a word on the grid"
      />,
    );
    expect(
      screen.getByText("Ce signal est trop proche d'un mot de la grille."),
    ).toBeInTheDocument();
  });

  it("lets the lookouts in play pick a hidden word or pass", async () => {
    const props = handlers();
    const room = guessing(lookout.room as RoomView);
    const first = room.board[0]?.word ?? "";
    render(<Table room={room} {...props} />);
    expect(screen.getByText(/encore 3 au plus/)).toBeInTheDocument();
    expect(screen.getByText("voyage")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Signal : voyage, 2.");
    await userEvent.click(screen.getByRole("button", { name: first }));
    expect(props.onReveal).toHaveBeenCalledWith(0);
    await userEvent.click(screen.getByRole("button", { name: "Passer" }));
    expect(props.onPass).toHaveBeenCalledOnce();
  });

  it("does not offer a revealed word, and waits for the answer of the server", async () => {
    const props = handlers();
    const room = guessing(lookout.room as RoomView);
    room.board = room.board.map((card, index) =>
      index === 0 ? { ...card, revealed: true, kind: "BABORD" } : card,
    );
    const { rerender } = render(<Table room={room} {...props} />);
    expect(
      screen.queryByRole("button", { name: room.board[0]?.word }),
    ).toBeNull();
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(23);
    rerender(<Table room={room} {...props} busy />);
    expect(screen.getByRole("button", { name: "Passer" })).toBeDisabled();
    const second = screen.getByRole("button", { name: room.board[1]?.word });
    expect(second).toHaveAttribute("aria-disabled", "true");
    await userEvent.click(second);
    expect(props.onReveal).not.toHaveBeenCalled();
  });

  it("asks before a player leaves a game in progress", async () => {
    const props = handlers();
    const { unmount } = render(
      <Table room={lookout.room as RoomView} {...props} />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Quitter" }));
    const dialog = await screen.findByRole("dialog", {
      name: "Quitter la partie ?",
    });
    expect(dialog).toHaveTextContent("Tu ne pourras pas reprendre ta place.");
    expect(dialog).not.toHaveTextContent("Signaleur");
    await userEvent.click(
      within(dialog).getByRole("button", { name: "Rester" }),
    );
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(props.onLeave).not.toHaveBeenCalled();

    await userEvent.click(screen.getByRole("button", { name: "Quitter" }));
    await userEvent.click(
      within(await screen.findByRole("dialog")).getByRole("button", {
        name: "Quitter",
      }),
    );
    expect(props.onLeave).toHaveBeenCalledOnce();
    unmount();

    render(<Table room={signaler.room as RoomView} {...handlers()} />);
    await userEvent.click(screen.getByRole("button", { name: "Quitter" }));
    expect(await screen.findByRole("dialog")).toHaveTextContent(
      "Tu es Signaleur : sans toi, la partie sera bloquée.",
    );
  });

  it("gives the others nothing to click during a guess", () => {
    const base = lookout.room as RoomView;
    const { unmount } = render(
      <Table room={guessing(base, { turn: "TRIBORD" })} {...handlers()} />,
    );
    expect(
      screen.getByText("Les Veilleurs de Tribord cherchent."),
    ).toBeVisible();
    expect(screen.queryByRole("button", { name: "Passer" })).toBeNull();
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);
    unmount();

    render(
      <Table room={guessing(signaler.room as RoomView)} {...handlers()} />,
    );
    expect(screen.getByText("Tes Veilleurs cherchent…")).toBeVisible();
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);
    expect(screen.queryByLabelText("Ton signal")).toBeNull();
  });

  it("announces the winner, shows the whole key and leads back home", async () => {
    const props = handlers();
    const room = {
      ...(lookout.room as RoomView),
      phase: "ENDED" as const,
      turn: "TRIBORD" as const,
      winner: "TRIBORD" as const,
      board: (signaler.room as RoomView).board,
    };
    render(<Table room={room} {...props} />);
    expect(
      screen.getByRole("heading", { name: "Tribord gagne" }),
    ).toBeInTheDocument();
    expect(document.querySelectorAll(".sem-word-mark")).toHaveLength(24);
    expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);
    expect(screen.queryByRole("button", { name: "Passer" })).toBeNull();
    await userEvent.click(
      screen.getByRole("button", { name: "Retour à l'accueil" }),
    );
    expect(props.onLeave).toHaveBeenCalledOnce();
  });
});
