package fr.didelo.semaphore.ws;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.Moves;
import fr.didelo.semaphore.game.MutableClock;
import fr.didelo.semaphore.game.Phase;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.Team;
import fr.didelo.semaphore.game.WordList;
import fr.didelo.semaphore.store.InMemoryRoomEvents;
import fr.didelo.semaphore.store.InMemoryRoomStore;
import fr.didelo.semaphore.store.LocalCluster;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The bots playing the rooms they are handed, alone or on two replicas at once (ADR 0016). */
class BotTurnsTest {

  private final MutableClock clock = new MutableClock();
  private final RoomRegistry registry =
      new RoomRegistry(new InMemoryRoomStore(), clock, new WordList(), 2, List.of());
  private final GameSocketHandler handler =
      new GameSocketHandler(
          JsonMapper.builder().build(), registry, new InMemoryRoomEvents(), new LocalCluster());

  private String code;
  private String alice;

  /**
   * Alice is a lookout on Bâbord, whose Signaleur is a bot; Tribord is a bot Signaleur and a bot
   * lookout. The game is started, with the turn of {@code first}.
   */
  private void started(Team first) {
    do {
      var created = registry.create("Alice", null, Moves.REPLICA);
      code = created.room().code();
      alice = created.result().getId();
      registry.change(
          code,
          room -> {
            room.apply(alice, new Command.JoinTeam(Team.BABORD));
            room.apply(alice, new Command.AddBot(Team.BABORD, true, "HARD"));
            room.apply(alice, new Command.AddBot(Team.TRIBORD, true, "NORMAL"));
            room.apply(alice, new Command.AddBot(Team.TRIBORD, false, "EASY"));
            room.apply(alice, new Command.Start());
          });
    } while (room().state().turn() != first);
  }

  private Room room() {
    return registry.find(code).orElseThrow();
  }

  private long revealed() {
    return room().state().board().stream().filter(card -> card.revealed()).count();
  }

  /** Alice, a lookout, passes when her team waits for her. */
  private void alicePasses() {
    if (room().phase() == Phase.GUESS && room().botToAct().isEmpty()) {
      registry.change(code, room -> room.apply(alice, new Command.Pass()));
    }
  }

  private void until(Predicate<Room> done, Runnable each) throws InterruptedException {
    var deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (!done.test(room())) {
      assertThat(System.nanoTime()).as("the game goes on").isLessThan(deadline);
      each.run();
      Thread.sleep(5);
    }
  }

  @Test
  void aGameWithBotsInBothRolesOfATeamRunsToTheEnd() throws Exception {
    started(Team.TRIBORD);
    var bots = TestBots.turns(registry, handler, Duration.ZERO);

    until(
        room -> room.phase() == Phase.ENDED,
        () -> {
          bots.consider(room());
          alicePasses();
        });

    assertThat(room().viewFor(alice).winner()).isNotNull();
    assertThat(revealed()).isPositive();
  }

  @Test
  void twoReplicasThatSeeTheSameTurnFinishTheGameBetweenThem() throws Exception {
    started(Team.BABORD);
    var first = TestBots.turns(registry, handler, Duration.ZERO);
    var second = TestBots.turns(registry, handler, Duration.ZERO);

    until(
        room -> room.phase() == Phase.ENDED,
        () -> {
          first.consider(room());
          second.consider(room());
          alicePasses();
        });

    assertThat(room().viewFor(alice).winner()).isNotNull();
  }

  /** What another replica did first must not be done again by the one that decided before it. */
  @Test
  void aBotThatWokeUpToAGameThatMovedOnDoesNothing() throws Exception {
    started(Team.TRIBORD);
    var bots = TestBots.turns(registry, handler, Duration.ofSeconds(1));
    var signaler = room().botToAct().orElseThrow();
    registry.change(
        code, room -> room.apply(signaler.getId(), new Command.Signal("zzyzx", Room.MAX_NUMBER)));
    var lookout = room().botToAct().orElseThrow();
    bots.consider(room());
    // The bot has read the room by now, and is thinking.
    Thread.sleep(300);

    clock.advance(Duration.ofSeconds(1));
    registry.change(
        code,
        room ->
            room.apply(
                lookout.getId(), new Command.Reveal(Moves.indexOf(room, Team.TRIBORD.kind(), 0))));
    Thread.sleep(1_300);

    assertThat(revealed()).isEqualTo(1);
    assertThat(room().phase()).isEqualTo(Phase.GUESS);
    assertThat(room().state().turn()).isEqualTo(Team.TRIBORD);
  }

  @Test
  void aBotDoesNotPlayWhileNoHumanIsOnline() throws Exception {
    started(Team.TRIBORD);
    registry.change(code, room -> room.disconnect(alice, Moves.REPLICA));
    var bots = TestBots.turns(registry, handler, Duration.ZERO);

    bots.consider(room());
    Thread.sleep(300);

    assertThat(room().phase()).isEqualTo(Phase.SIGNAL);
    assertThat(room().state().signal()).isNull();
  }
}
