package fr.didelo.semaphore.game;

/** What a player can ask a room to do once inside it. The room decides whether it is legal. */
public sealed interface Command {

  record JoinTeam(Team team) implements Command {}

  record Start() implements Command {}

  record Signal(String word, int number) implements Command {}

  record Reveal(int index) implements Command {}

  record Pass() implements Command {}
}
