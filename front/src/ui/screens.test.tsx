import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ComponentProps } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import lookout from "../fixtures/room-lookout.json";
import signaler from "../fixtures/room-signaler.json";
import spectator from "../fixtures/room-spectator.json";
import waiting from "../fixtures/room-waiting.json";
import type { RoomView } from "../protocol.ts";
import type { Account } from "./Account.tsx";
import { Home } from "./Home.tsx";
import { Lobby } from "./Lobby.tsx";
import { Table } from "./Table.tsx";

const lobby = (changes: Partial<RoomView>, you = "a"): RoomView => ({
  code: "USQ2H",
  version: 1,
  phase: "LOBBY",
  turn: null,
  turnPassesIn: null,
  signal: null,
  revealsLeft: 0,
  flags: { BABORD: 2, TRIBORD: 2 },
  winner: null,
  players: [],
  spectators: 0,
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

const nobody: Pick<ComponentProps<typeof Home>, "signIn" | "account"> = {
  signIn: {
    providers: [],
    returned: null,
    resume: null,
    failed: false,
    onSignIn: vi.fn(),
  },
  account: null,
};

describe("Home", () => {
  it("creates a room with the name typed", async () => {
    const onCreate = vi.fn();
    render(
      <Home
        invited={null}
        initialName=""
        remembered={false}
        busy={false}
        error={null}
        {...nobody}
        onCreate={onCreate}
        onJoin={vi.fn()}
        onWatch={vi.fn()}
      />,
    );
    const create = screen.getByRole("button", { name: "Créer un salon" });
    expect(create).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Ton nom"), "Ana");
    await userEvent.click(create);
    await waitFor(() => expect(onCreate).toHaveBeenCalledWith("Ana"));
  });

  it("starts from the name the server remembers and says so", async () => {
    const onCreate = vi.fn();
    render(
      <Home
        invited={null}
        initialName="Ana"
        remembered
        busy={false}
        error={null}
        {...nobody}
        onCreate={onCreate}
        onJoin={vi.fn()}
        onWatch={vi.fn()}
      />,
    );
    expect(screen.getByLabelText("Ton nom")).toHaveValue("Ana");
    expect(screen.getByText(/retenu sur cet appareil/)).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Créer un salon" }),
    );
    await waitFor(() => expect(onCreate).toHaveBeenCalledWith("Ana"));
  });

  it("joins by code, upper-cased", async () => {
    const onJoin = vi.fn();
    render(
      <Home
        invited={null}
        initialName=""
        remembered={false}
        busy={false}
        error={null}
        {...nobody}
        onCreate={vi.fn()}
        onJoin={onJoin}
        onWatch={vi.fn()}
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
        initialName=""
        remembered={false}
        busy={false}
        error="no such room"
        {...nobody}
        onCreate={vi.fn()}
        onJoin={onJoin}
        onWatch={vi.fn()}
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

  it("lets a visitor of an invite link watch without a name, and offers it only there", async () => {
    const onWatch = vi.fn();
    const { unmount } = render(
      <Home
        invited="USQ2H"
        initialName=""
        remembered={false}
        busy={false}
        error="the game has started"
        {...nobody}
        onCreate={vi.fn()}
        onJoin={vi.fn()}
        onWatch={onWatch}
      />,
    );
    expect(screen.getByText("La partie a déjà commencé.")).toBeInTheDocument();
    await userEvent.click(
      screen.getByRole("button", { name: "Regarder la partie" }),
    );
    expect(onWatch).toHaveBeenCalledWith("USQ2H");
    unmount();

    render(
      <Home
        invited={null}
        initialName=""
        remembered={false}
        busy={false}
        error={null}
        {...nobody}
        onCreate={vi.fn()}
        onJoin={vi.fn()}
        onWatch={onWatch}
      />,
    );
    expect(
      screen.queryByRole("button", { name: "Regarder la partie" }),
    ).toBeNull();
  });
});

describe("Home sign-in", () => {
  const home = (
    signIn: Partial<ComponentProps<typeof Home>["signIn"]>,
    rest: Partial<ComponentProps<typeof Home>> = {},
  ) =>
    render(
      <Home
        invited={null}
        initialName=""
        remembered={false}
        busy={false}
        error={null}
        signIn={{ ...nobody.signIn, ...signIn }}
        account={null}
        onCreate={vi.fn()}
        onJoin={vi.fn()}
        onWatch={vi.fn()}
        {...rest}
      />,
    );

  it("offers no sign-in when the server lists no provider, or one it does not draw", () => {
    const { unmount } = home({});
    expect(screen.queryByRole("button", { name: /Se connecter/ })).toBeNull();
    unmount();

    home({ providers: ["gitlab"] });
    expect(screen.queryByRole("button", { name: /Se connecter/ })).toBeNull();
  });

  it("starts a login with the name typed, for the provider chosen", async () => {
    const onSignIn = vi.fn();
    home({ providers: ["github", "google"], onSignIn });
    const github = screen.getByRole("button", {
      name: "Se connecter avec GitHub",
    });
    expect(github).toBeDisabled();
    await userEvent.type(screen.getByLabelText("Ton nom"), "Ana");
    await userEvent.click(
      screen.getByRole("button", { name: "Se connecter avec Google" }),
    );
    await waitFor(() => expect(onSignIn).toHaveBeenCalledWith("google", "Ana"));
  });

  it("does not offer a login inside an invitation", () => {
    home({ providers: ["github"] }, { invited: "USQ2H" });
    expect(screen.queryByRole("button", { name: /Se connecter/ })).toBeNull();
  });

  it("says when a login failed", () => {
    home({ providers: ["github"], returned: "failed" });
    expect(screen.getByText(/La connexion a échoué/)).toBeInTheDocument();
  });

  it("asks for the pseudo and picks the login up again where it stopped", () => {
    home({
      providers: ["github", "google"],
      returned: "pseudo",
      resume: "github",
    });
    expect(screen.getByText(/Dis-nous comment t'appeler/)).toHaveTextContent(
      "avec GitHub",
    );
    expect(
      screen.getByRole("button", { name: "Continuer avec GitHub" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Google/ })).toBeNull();
  });

  it("offers every provider again when the login to resume is not one it draws", () => {
    home({
      providers: ["github", "google"],
      returned: "pseudo",
      resume: "gitlab",
    });
    expect(
      screen.getByRole("button", { name: "Se connecter avec Google" }),
    ).toBeInTheDocument();
  });

  it("says a login did not start when the name was refused", () => {
    home({ providers: ["github"], failed: true });
    expect(screen.getByText(/n'a pas pu être enregistré/)).toBeInTheDocument();
  });

  describe("signed in", () => {
    const account = (
      changes: Partial<ComponentProps<typeof Account>> = {},
    ) => ({
      name: "Ana",
      busy: false,
      failed: false,
      onSignOut: vi.fn(),
      onDelete: vi.fn(),
      ...changes,
    });

    it("shows the account and signs out", async () => {
      const onSignOut = vi.fn();
      home({ providers: ["github"] }, { account: account({ onSignOut }) });
      expect(screen.getByText(/Connecté en tant que/)).toHaveTextContent("Ana");
      expect(screen.queryByRole("button", { name: /Se connecter/ })).toBeNull();
      await userEvent.click(
        screen.getByRole("button", { name: "Se déconnecter" }),
      );
      expect(onSignOut).toHaveBeenCalledOnce();
    });

    it("deletes the account only once the player confirmed", async () => {
      const onDelete = vi.fn();
      home({}, { account: account({ onDelete }) });
      await userEvent.click(
        screen.getByRole("button", { name: "Supprimer mon compte" }),
      );
      const dialog = await screen.findByRole("dialog");
      expect(onDelete).not.toHaveBeenCalled();

      await userEvent.click(
        within(dialog).getByRole("button", { name: "Garder mon compte" }),
      );
      await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
      expect(onDelete).not.toHaveBeenCalled();

      await userEvent.click(
        screen.getByRole("button", { name: "Supprimer mon compte" }),
      );
      await userEvent.click(
        within(await screen.findByRole("dialog")).getByRole("button", {
          name: "Supprimer",
        }),
      );
      expect(onDelete).toHaveBeenCalledOnce();
    });

    it("says when the server did not answer", () => {
      home({}, { account: account({ failed: true }) });
      expect(screen.getByText(/n'a pas répondu/)).toBeInTheDocument();
    });
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

describe("Presence", () => {
  const noop = vi.fn();

  it("counts who is online in the lobby, dims the others and holds the start", () => {
    const players = [
      player("a", "BABORD", true),
      player("b", "BABORD"),
      { ...player("c", "TRIBORD"), connected: false },
      player("d", "TRIBORD"),
    ];
    render(
      <Lobby
        room={lobby({ players })}
        error={null}
        onJoinTeam={noop}
        onStart={noop}
        onLeave={noop}
      />,
    );
    expect(screen.getByText("3 sur 4 en ligne")).toBeInTheDocument();
    expect(screen.getByText("c · hors ligne").closest("li")).toHaveAttribute(
      "data-online",
      "false",
    );
    expect(screen.getByText("a (toi) · hôte").closest("li")).toHaveAttribute(
      "data-online",
      "true",
    );
    expect(
      screen.getByRole("button", { name: "Lancer la partie" }),
    ).toBeDisabled();
    expect(
      screen.getByText(
        "c : hors ligne. Lance la partie quand tout le monde est de retour.",
      ),
    ).toBeInTheDocument();
  });

  it("starts again once everybody is back", () => {
    const players = [
      player("a", "BABORD", true),
      player("b", "BABORD"),
      player("c", "TRIBORD"),
      player("d", "TRIBORD"),
    ];
    render(
      <Lobby
        room={lobby({ players })}
        error={null}
        onJoinTeam={noop}
        onStart={noop}
        onLeave={noop}
      />,
    );
    expect(screen.getByText("4 en ligne")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Lancer la partie" }),
    ).toBeEnabled();
    expect(screen.queryByText(/hors ligne/)).toBeNull();
  });

  const without = (room: RoomView, team: string, signaler: boolean) => ({
    ...room,
    players: room.players.map((p) =>
      p.team === team && p.signaler === signaler
        ? { ...p, connected: false }
        : p,
    ),
  });
  const props = {
    busy: false,
    error: null,
    onSignal: vi.fn(),
    onReveal: vi.fn(),
    onPass: vi.fn(),
    onLeave: vi.fn(),
  };

  it("says at the table who the game waits for: the Signaleur in play", () => {
    render(
      <Table
        room={without(signaler.room as RoomView, "TRIBORD", true)}
        {...props}
      />,
    );
    expect(
      screen.getByText(
        "Ben, le Signaleur de Tribord, est hors ligne : la partie l'attend.",
      ),
    ).toBeVisible();
    expect(screen.getByText("3 sur 4 en ligne")).toBeInTheDocument();
    expect(screen.getByText(/Ben · Signaleur/).closest("li")).toHaveAttribute(
      "data-online",
      "false",
    );
  });

  it("says at the table who the game waits for: the lookouts in play", () => {
    const room = {
      ...without(signaler.room as RoomView, "BABORD", false),
      phase: "GUESS" as const,
      turn: "BABORD" as const,
      signal: { word: "voyage", number: 2 },
      revealsLeft: 3,
    };
    render(<Table room={room} {...props} />);
    expect(
      screen.getByText(
        "Les Veilleurs de Bâbord sont hors ligne : la partie les attend.",
      ),
    ).toBeVisible();
  });

  it("does not say a Signaleur is missed while the lookouts are playing", () => {
    const room = {
      ...without(lookout.room as RoomView, "TRIBORD", true),
      phase: "GUESS" as const,
      turn: "TRIBORD" as const,
      signal: { word: "voyage", number: 2 },
      revealsLeft: 3,
    };
    render(<Table room={room} {...props} />);
    expect(
      screen.getByText("Les Veilleurs de Tribord cherchent."),
    ).toBeVisible();
    expect(screen.queryByRole("timer")).toBeNull();
  });

  describe("when the turn is about to pass", () => {
    afterEach(() => vi.useRealTimers());

    it("shows the seconds left next to the line that says who the game waits for, and counts down", () => {
      vi.useFakeTimers();
      render(<Table room={waiting.room as RoomView} {...props} />);
      expect(screen.getByRole("status")).toHaveTextContent(
        "Ben, le Signaleur de Tribord, est hors ligne : la partie l'attend.",
      );
      expect(screen.getByRole("status")).not.toHaveTextContent("Le tour passe");
      expect(screen.getByRole("timer")).toHaveTextContent(
        "Le tour passe dans 60 s.",
      );

      act(() => vi.advanceTimersByTime(2000));
      expect(screen.getByRole("timer")).toHaveTextContent(
        "Le tour passe dans 58 s.",
      );
      act(() => vi.advanceTimersByTime(120_000));
      expect(screen.getByRole("timer")).toHaveTextContent(
        "Le tour passe dans 0 s.",
      );
    });

    it("starts the count again with every new view and drops it when nobody is waited for", () => {
      vi.useFakeTimers();
      const room = waiting.room as RoomView;
      const { rerender } = render(<Table room={room} {...props} />);
      act(() => vi.advanceTimersByTime(5000));

      rerender(
        <Table
          room={{ ...room, version: room.version + 1, turnPassesIn: 60 }}
          {...props}
        />,
      );
      expect(screen.getByRole("timer")).toHaveTextContent(
        "Le tour passe dans 60 s.",
      );
      act(() => vi.advanceTimersByTime(1000));
      expect(screen.getByRole("timer")).toHaveTextContent(
        "Le tour passe dans 59 s.",
      );

      rerender(
        <Table
          room={{ ...room, version: room.version + 2, turnPassesIn: null }}
          {...props}
        />,
      );
      expect(screen.queryByRole("timer")).toBeNull();
    });
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
      "Tu es Signaleur : sans toi, ton équipe ne peut plus jouer et finira par perdre la partie.",
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

  describe("for a spectator", () => {
    const watcher = spectator.room as RoomView;

    it("is read only: no form, no word to touch, no pass, and says so", () => {
      const { unmount } = render(<Table room={watcher} {...handlers()} />);
      expect(
        screen.getByText("Tu regardes la partie sans y jouer."),
      ).toBeVisible();
      expect(
        screen.getByText("Le Signaleur de Tribord prépare son signal."),
      ).toBeVisible();
      expect(screen.queryByLabelText("Ton signal")).toBeNull();
      expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);
      expect(screen.queryByText(/\(toi\)/)).toBeNull();
      unmount();

      // Even in a team's turn with its words to touch, nothing is a button for them.
      render(<Table room={guessing(watcher)} {...handlers()} />);
      expect(
        screen.getByText("Les Veilleurs de Bâbord cherchent."),
      ).toBeVisible();
      expect(document.querySelectorAll(".sem-word-button")).toHaveLength(0);
      expect(screen.queryByRole("button", { name: "Passer" })).toBeNull();
    });

    it("sees what a lookout sees: no key on a hidden word", () => {
      render(<Table room={watcher} {...handlers()} />);
      expect(document.querySelectorAll(".sem-word-mark")).toHaveLength(0);
    });

    it("leaves at once, with nothing to lose, and sees the whole key once the game has ended", async () => {
      const props = handlers();
      const { unmount } = render(<Table room={watcher} {...props} />);
      await userEvent.click(screen.getByRole("button", { name: "Quitter" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(props.onLeave).toHaveBeenCalledOnce();
      unmount();

      render(
        <Table
          room={{
            ...watcher,
            phase: "ENDED",
            winner: "BABORD",
            board: (signaler.room as RoomView).board,
          }}
          {...handlers()}
        />,
      );
      expect(document.querySelectorAll(".sem-word-mark")).toHaveLength(24);
      expect(
        screen.getByRole("button", { name: "Retour à l'accueil" }),
      ).toBeInTheDocument();
    });

    it("says how many people watch, to the players as well", () => {
      const { unmount } = render(<Table room={watcher} {...handlers()} />);
      expect(screen.getByText("4 en ligne · 1 personne regarde")).toBeVisible();
      unmount();

      const { unmount: again } = render(
        <Table
          room={{ ...(lookout.room as RoomView), spectators: 3 }}
          {...handlers()}
        />,
      );
      expect(
        screen.getByText("4 en ligne · 3 personnes regardent"),
      ).toBeVisible();
      expect(
        screen.queryByText("Tu regardes la partie sans y jouer."),
      ).toBeNull();
      again();

      render(<Table room={lookout.room as RoomView} {...handlers()} />);
      expect(screen.getByText("4 en ligne")).toBeVisible();
    });

    it("shows why the server refuses a command, in French", () => {
      render(
        <Table room={watcher} {...handlers()} error="spectators cannot play" />,
      );
      expect(
        screen.getByText("Un spectateur regarde sans jouer."),
      ).toBeVisible();
    });
  });
});
