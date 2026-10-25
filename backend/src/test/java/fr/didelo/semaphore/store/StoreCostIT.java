package fr.didelo.semaphore.store;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.Valkey;
import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.RoomStore;
import fr.didelo.semaphore.game.Team;
import fr.didelo.semaphore.game.WordList;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a command costs in the store, in memory and on a Valkey on this machine. It measures more
 * than it checks: the figures are printed for the README, the limit only catches a disaster.
 */
class StoreCostIT {

  private static final int ROUNDS = 3_000;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A started room of four players: the biggest state a room has, a 25-card board. */
  private static String startedRoom(RoomRegistry registry) {
    var code = registry.create("p0", "replica").room().code();
    for (var i = 1; i < 4; i++) {
      var name = "p" + i;
      registry.update(code, room -> room.join(name, "replica"));
    }
    var ids =
        registry.find(code).orElseThrow().state().players().stream().map(p -> p.id()).toList();
    for (var i = 0; i < 4; i++) {
      var id = ids.get(i);
      var team = i < 2 ? Team.BABORD : Team.TRIBORD;
      registry.change(code, room -> room.apply(id, new Command.JoinTeam(team)));
    }
    registry.change(code, room -> room.apply(ids.getFirst(), new Command.Start()));
    return code;
  }

  private static double micros(Runnable work) {
    for (var i = 0; i < ROUNDS; i++) {
      work.run();
    }
    var start = System.nanoTime();
    for (var i = 0; i < ROUNDS; i++) {
      work.run();
    }
    return (System.nanoTime() - start) / 1_000.0 / ROUNDS;
  }

  private static void measure(String name, RoomStore store) {
    var registry = new RoomRegistry(store, Clock.systemUTC(), new WordList(), 2, List.of());
    var code = startedRoom(registry);
    var state = store.find(code).orElseThrow();
    var bytes = JSON.writeValueAsBytes(state).length;
    var load = micros(() -> store.find(code));
    var save =
        micros(
            () -> {
              var current = store.find(code).orElseThrow();
              store.replace(next(current), current.version());
            });
    var command =
        micros(
                () -> {
                  registry.change(code, room -> room.watch("replica"));
                  registry.change(code, room -> room.unwatch("replica"));
                })
            / 2;
    System.out.printf(
        "COST %-9s state %d bytes | load %.0f us | save (load+replace) %.0f us | command (load+apply+save) %.0f us%n",
        name, bytes, load, save, command);
    assertThat(command).as("a command in " + name).isLessThan(25_000);
  }

  private static RoomState next(RoomState s) {
    return new RoomState(
        s.code(),
        s.version() + 1,
        s.phase(),
        s.turn(),
        s.signal(),
        s.revealsLeft(),
        s.winner(),
        s.flags(),
        s.board(),
        s.players(),
        s.spectators(),
        s.lastActivity(),
        s.lastMove(),
        s.gameId(),
        s.endedAt());
  }

  @Test
  void inMemory() {
    measure("memory", new InMemoryRoomStore());
  }

  @Test
  void onValkey() {
    var link = new RedisLink(Valkey.url());
    try {
      measure("valkey", new RedisRoomStore(link, JSON));
    } finally {
      link.close();
    }
  }
}
