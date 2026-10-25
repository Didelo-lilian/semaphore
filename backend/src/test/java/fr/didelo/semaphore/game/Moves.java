package fr.didelo.semaphore.game;

import java.time.Clock;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;

/** Plays a room through its commands, as the players of a table would. */
public final class Moves {

  // Distinct first letters: no word of the pool is too close to another one.
  public static final List<String> POOL =
      IntStream.range(0, 26).mapToObj(i -> (char) ('a' + i) + "voile" + (char) ('a' + i)).toList();

  private static final String REPLICA = "replica-1";

  private Moves() {}

  /**
   * A game of BABORD against TRIBORD, two players each (p0 to p3, the first of each team the
   * Signaleur), that BABORD has won. {@code account}, if not null, is the account of p1.
   */
  public static RoomState ended(UUID account) {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(7), 2, POOL);
    var players = IntStream.range(0, 4).mapToObj(i -> room.join("p" + i, REPLICA)).toList();
    players.get(1).setAccountId(account);
    players.forEach(
        player ->
            room.apply(
                player.getId(),
                new Command.JoinTeam(players.indexOf(player) < 2 ? Team.BABORD : Team.TRIBORD)));
    room.apply(players.get(0).getId(), new Command.Start());
    win(room, Team.BABORD);
    return room.state();
  }

  /**
   * The code of a started game that {@code registry} holds, with the same four players as {@link
   * #ended}; {@code account}, if not null, is the account of p1.
   */
  public static String startedGame(RoomRegistry registry, UUID account) {
    var code = registry.create("p0", REPLICA).room().code();
    registry.change(
        code,
        room -> {
          room.join("p1", REPLICA).setAccountId(account);
          room.join("p2", REPLICA);
          room.join("p3", REPLICA);
        });
    registry.change(
        code,
        room -> {
          var players = room.state().players();
          IntStream.range(0, 4)
              .forEach(
                  i ->
                      room.apply(
                          players.get(i).id(),
                          new Command.JoinTeam(i < 2 ? Team.BABORD : Team.TRIBORD)));
          room.apply(players.get(0).id(), new Command.Start());
        });
    return code;
  }

  /** The nth member of a team, in the order they joined: the first one is the Signaleur. */
  private static String member(Room room, Team team, int nth) {
    return room.state().players().stream()
        .filter(player -> player.team() == team)
        .skip(nth)
        .findFirst()
        .orElseThrow()
        .id();
  }

  public static void signal(Room room, String word, int number) {
    room.apply(member(room, room.state().turn(), 0), new Command.Signal(word, number));
  }

  public static void reveal(Room room, int index) {
    room.apply(member(room, room.state().turn(), 1), new Command.Reveal(index));
  }

  public static void pass(Room room) {
    room.apply(member(room, room.state().turn(), 1), new Command.Pass());
  }

  /** The nth card of a kind, whether it is revealed or not. */
  public static int indexOf(Room room, CardKind kind, int nth) {
    var board = room.state().board();
    var seen = 0;
    for (var i = 0; i < board.size(); i++) {
      if (board.get(i).kind() == kind && seen++ == nth) {
        return i;
      }
    }
    throw new IllegalArgumentException("no such card");
  }

  /** Plays until {@code winner} has revealed all its words, the other team passing each turn. */
  public static void win(Room room, Team winner) {
    var revealed = 0;
    while (room.phase() != Phase.ENDED) {
      var inPlay = room.state().turn();
      signal(room, "zzyzx", Room.MAX_NUMBER);
      if (inPlay != winner) {
        pass(room);
        continue;
      }
      for (var i = 0; i <= Room.MAX_NUMBER && room.phase() != Phase.ENDED; i++) {
        reveal(room, indexOf(room, winner.kind(), revealed++));
      }
    }
  }
}
