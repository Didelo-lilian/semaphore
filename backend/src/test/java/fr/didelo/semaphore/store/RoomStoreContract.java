package fr.didelo.semaphore.store;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.MutableClock;
import fr.didelo.semaphore.game.Phase;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.RoomStore;
import fr.didelo.semaphore.game.Team;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** What every store must do, whatever it keeps the rooms in. */
abstract class RoomStoreContract {

  private static final List<String> POOL =
      IntStream.range(0, 26).mapToObj(i -> (char) ('a' + i) + "voile" + (char) ('a' + i)).toList();

  abstract RoomStore store();

  /** A code no other test uses, since a real store is shared by the tests of a run. */
  static String newCode() {
    return UUID.randomUUID().toString().substring(0, 5).toUpperCase();
  }

  static Room lobby(String code) {
    var room = new Room(code, new MutableClock(), new Random(1), 2, POOL);
    room.join("ana", "replica-1");
    return room;
  }

  @Test
  void aRoomComesBackAsItWasSaved() {
    var room = new Room(newCode(), new MutableClock(), new Random(1), 2, POOL);
    for (var name : List.of("ana", "bob", "cleo", "dan")) {
      room.join(name, "replica-1");
    }
    var ids = room.state().players().stream().map(RoomState.PlayerState::id).toList();
    for (var i = 0; i < ids.size(); i++) {
      room.apply(ids.get(i), new Command.JoinTeam(i < 2 ? Team.BABORD : Team.TRIBORD));
    }
    room.apply(ids.get(0), new Command.Start());
    room.disconnect(ids.get(3), "replica-1");
    room.watch("replica-1");

    assertThat(store().create(room.state())).isTrue();

    assertThat(store().find(room.code())).contains(room.state());
  }

  @Test
  void aRoomWithEmptyListsComesBackWithEmptyLists() {
    // A Lua script that decoded and encoded the JSON would turn each [] into {}.
    var empty =
        new RoomState(
            newCode(),
            1,
            Phase.LOBBY,
            null,
            null,
            0,
            null,
            Map.of(),
            List.of(),
            List.of(),
            Map.of(),
            Instant.parse("2026-10-25T10:00:00.123456789Z"),
            null);

    store().create(empty);

    assertThat(store().find(empty.code())).contains(empty);
  }

  @Test
  void aRoomThatDoesNotExistIsNotFound() {
    assertThat(store().find(newCode())).isEmpty();
  }

  @Test
  void aCodeThatIsTakenIsRefusedAndTheRoomIsLeftAlone() {
    var first = lobby(newCode());
    store().create(first.state());
    var second = new Room(first.code(), new MutableClock(), new Random(2), 2, POOL);
    second.join("bob", "replica-1");

    assertThat(store().create(second.state())).isFalse();

    assertThat(store().find(first.code())).contains(first.state());
  }

  @Test
  void aRoomIsReplacedOnlyByTheOneWhoLoadedTheLatestVersion() {
    var room = lobby(newCode());
    store().create(room.state());
    var loaded = room.version();
    room.join("bob", "replica-1");

    assertThat(store().replace(room.state(), loaded)).isTrue();
    room.join("cleo", "replica-1");
    assertThat(store().replace(room.state(), loaded)).isFalse();

    assertThat(store().find(room.code()).orElseThrow().players()).hasSize(2);
  }

  @Test
  void aRoomThatWasDroppedIsNotReplaced() {
    var room = lobby(newCode());
    var loaded = room.version();
    room.join("bob", "replica-1");

    assertThat(store().replace(room.state(), loaded)).isFalse();

    assertThat(store().find(room.code())).isEmpty();
  }

  @Test
  void aRoomIsDroppedOnlyAtTheVersionThatWasSeen() {
    var room = lobby(newCode());
    store().create(room.state());
    var seen = room.version();
    room.join("bob", "replica-1");
    store().replace(room.state(), seen);

    assertThat(store().drop(room.code(), seen)).isFalse();
    assertThat(store().find(room.code())).isPresent();
    assertThat(store().drop(room.code(), room.version())).isTrue();
    assertThat(store().find(room.code())).isEmpty();
    assertThat(store().drop(room.code(), room.version())).isFalse();
  }

  @Test
  void theCodesAreThoseOfTheRoomsThatExist() {
    var kept = lobby(newCode());
    var dropped = lobby(newCode());
    store().create(kept.state());
    store().create(dropped.state());
    store().drop(dropped.code(), dropped.version());

    assertThat(store().codes()).contains(kept.code()).doesNotContain(dropped.code());
  }
}
