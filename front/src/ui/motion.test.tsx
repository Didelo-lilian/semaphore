import { fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Card, RoomView } from "../protocol.ts";
import { Lobby } from "./Lobby.tsx";
import { Board } from "./Table.tsx";

// The setup answers "no motion" to every query, like a browser that asks for reduced motion.
const allowMotion = () =>
  vi
    .spyOn(window, "matchMedia")
    .mockImplementation(
      (media) => ({ matches: true, media }) as MediaQueryList,
    );

afterEach(() => vi.restoreAllMocks());

// jsdom has no AnimationEvent, so React listens for the prefixed name and ignores fireEvent.animationEnd.
const endAnimation = (element: Element) =>
  fireEvent(element, new Event("webkitAnimationEnd", { bubbles: true }));

describe("a card turning over", () => {
  const board = (revealed: number[]): Card[] =>
    ["avion", "banc", "cloche"].map((word, index) => ({
      word,
      revealed: revealed.includes(index),
      kind: revealed.includes(index) ? "BABORD" : null,
    }));
  const card = (word: string) =>
    screen.getByRole("listitem", { name: new RegExp(`^${word},`) });

  it("animates when a view reveals it, not when it is already revealed on the first render", () => {
    allowMotion();
    const { rerender } = render(<Board board={board([0])} />);
    expect(card("avion")).toHaveAttribute("data-revealed", "true");
    expect(card("avion")).not.toHaveAttribute("data-motion");

    rerender(<Board board={board([0, 1])} />);
    expect(card("banc")).toHaveAttribute("data-revealed", "true");
    expect(card("banc")).toHaveAttribute("data-motion", "enter");
    expect(card("avion")).not.toHaveAttribute("data-motion");
    expect(card("cloche")).not.toHaveAttribute("data-motion");
  });

  it("keeps turning while other views arrive, and stops marking the card once it has turned", () => {
    allowMotion();
    const { rerender } = render(<Board board={board([])} />);
    rerender(<Board board={board([1])} />);
    rerender(<Board board={board([1, 2])} />);
    expect(card("banc")).toHaveAttribute("data-motion", "enter");
    expect(card("cloche")).toHaveAttribute("data-motion", "enter");

    endAnimation(card("banc"));
    expect(card("banc")).not.toHaveAttribute("data-motion");
    expect(card("cloche")).toHaveAttribute("data-motion", "enter");
    rerender(<Board board={board([1, 2])} />);
    expect(card("banc")).not.toHaveAttribute("data-motion");
  });

  it("changes colour at once and never moves when the browser asks for reduced motion", () => {
    const { rerender } = render(<Board board={board([])} />);
    rerender(<Board board={board([1])} />);
    expect(card("banc")).toHaveAttribute("data-revealed", "true");
    expect(card("banc")).not.toHaveAttribute("data-motion");
  });
});

describe("a player coming and going in the lobby", () => {
  const player = (id: string, team: "BABORD" | "TRIBORD" | null) => ({
    id,
    name: id,
    team,
    connected: true,
    host: id === "a",
    signaler: false,
  });
  const room = (players: RoomView["players"]): RoomView => ({
    code: "USQ2H",
    version: 1,
    phase: "LOBBY",
    turn: null,
    turnPassesIn: null,
    signal: null,
    revealsLeft: 0,
    flags: { BABORD: 2, TRIBORD: 2 },
    winner: null,
    players,
    spectators: 0,
    board: [],
    you: "a",
  });
  const lobby = (players: RoomView["players"]) => (
    <Lobby
      room={room(players)}
      error={null}
      onJoinTeam={vi.fn()}
      onStart={vi.fn()}
      onLeave={vi.fn()}
    />
  );
  const row = (name: string) =>
    screen.getByText(new RegExp(`^${name}`)).closest("li");
  const ghost = () => document.querySelector('[data-motion="leave"]');
  const [a, b, c] = [
    player("a", "BABORD"),
    player("b", "BABORD"),
    player("c", "TRIBORD"),
  ];

  it("animates the arrival of a player, not the players already there", () => {
    allowMotion();
    const { rerender } = render(lobby([a, b]));
    expect(document.querySelector("[data-motion]")).toBeNull();

    rerender(lobby([a, b, c]));
    expect(row("c")).toHaveAttribute("data-motion", "enter");
    expect(row("a")).not.toHaveAttribute("data-motion");
    expect(row("b")).not.toHaveAttribute("data-motion");

    endAnimation(row("c") as HTMLElement);
    expect(row("c")).not.toHaveAttribute("data-motion");
  });

  it("keeps a player who left as a hidden row that fades out, then drops it", () => {
    allowMotion();
    const { rerender } = render(lobby([a, b, c]));
    rerender(lobby([a, b]));

    // The count and the list for assistive technology are final at once.
    expect(screen.getByText("2 en ligne")).toBeInTheDocument();
    expect(screen.getAllByRole("listitem")).toHaveLength(2);
    expect(ghost()).toHaveAttribute("aria-hidden", "true");
    expect(ghost()).toHaveTextContent("c");

    endAnimation(ghost() as Element);
    expect(ghost()).toBeNull();
    expect(screen.queryByText(/^c/)).toBeNull();
  });

  it("does not play the arrival again when a player who has settled picks a team", () => {
    allowMotion();
    const onTheDock = player("b", null);
    const { rerender } = render(lobby([a]));
    rerender(lobby([a, onTheDock]));
    endAnimation(row("b") as HTMLElement);

    rerender(lobby([a, { ...onTheDock, team: "TRIBORD" }]));
    expect(row("b")).not.toHaveAttribute("data-motion");
    expect(document.querySelector("[data-motion]")).toBeNull();
  });

  it("keeps the dock for the fade of its last player and leaves nothing behind", () => {
    allowMotion();
    const { rerender } = render(lobby([a, player("b", null)]));
    rerender(lobby([a]));
    expect(ghost()).toHaveTextContent("b");
    expect(screen.getByText("Sur le quai")).toBeInTheDocument();

    endAnimation(ghost() as Element);
    expect(screen.queryByText("Sur le quai")).toBeNull();
    rerender(lobby([a, player("c", null)]));
    expect(ghost()).toBeNull();
    expect(row("c")).toHaveAttribute("data-motion", "enter");
  });

  it("takes the row back when the player returns before the fade ends", () => {
    allowMotion();
    const { rerender } = render(lobby([a, b, c]));
    rerender(lobby([a, b]));
    rerender(lobby([a, b, c]));
    expect(ghost()).toBeNull();
    expect(row("c")).toHaveAttribute("data-motion", "enter");
    expect(row("c")).not.toHaveAttribute("aria-hidden");
  });

  it("leaves a row in place, with no marker, when its player turns away and comes back", () => {
    allowMotion();
    const { rerender } = render(lobby([a, b, c]));
    const before = row("c");

    rerender(lobby([a, b, { ...c, connected: false }]));
    expect(row("c · hors ligne")).toBe(before);
    expect(before).toHaveAttribute("data-online", "false");
    expect(before).not.toHaveAttribute("data-motion");

    rerender(lobby([a, b, c]));
    expect(row("c")).toBe(before);
    expect(before).toHaveAttribute("data-online", "true");
    expect(before).not.toHaveAttribute("data-motion");
  });

  it("changes at once, with no marker and no row kept, when the browser asks for reduced motion", () => {
    const { rerender } = render(lobby([a, b]));
    rerender(lobby([a, b, c]));
    expect(document.querySelector("[data-motion]")).toBeNull();
    rerender(lobby([a, b]));
    expect(document.querySelector("[data-motion]")).toBeNull();
    expect(screen.queryByText(/^c/)).toBeNull();
  });
});
