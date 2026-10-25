package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.store.InMemoryRoomStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RoomRegistryTest {

  private final MutableClock clock = new MutableClock();
  private final RoomRegistry registry =
      new RoomRegistry(new InMemoryRoomStore(), clock, new WordList(), 2);

  @Test
  void aCodeHasFiveCharactersWithoutLookAlikes() {
    var codes =
        IntStream.range(0, 200).mapToObj(i -> registry.create("ana").room().code()).toList();

    assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-HJKMN-Z2-9]{5}"));
  }

  @Test
  void aRoomIsFoundWhateverTheCaseOrTheSpaces() {
    var code = registry.create("ana").room().code();

    assertThat(registry.find(" " + code.toLowerCase() + " ")).isPresent();
    assertThat(registry.find("00000")).isEmpty();
  }

  @Test
  void aRoomWithABadNameIsNotCreated() {
    assertThatThrownBy(() -> registry.create("   ")).isInstanceOf(IllegalMove.class);

    assertThat(registry.codes()).isEmpty();
  }

  @Test
  void theHostIsTheFirstPlayerOfANewRoom() {
    var created = registry.create("ana");

    assertThat(created.room().viewFor(created.result().getId()).players())
        .singleElement()
        .satisfies(p -> assertThat(p.host()).isTrue());
  }

  @Test
  void aChangeIsSavedAndFoundAgain() {
    var code = registry.create("ana").room().code();

    var joined = registry.update(code, room -> room.join("bob"));

    assertThat(joined.changed()).isTrue();
    assertThat(registry.find(code).orElseThrow().byToken(joined.result().getToken())).isPresent();
    assertThat(joined.room().version()).isEqualTo(registry.find(code).orElseThrow().version());
  }

  @Test
  void aChangeThatDoesNothingSavesNothing() {
    var code = registry.create("ana").room().code();
    var version = registry.find(code).orElseThrow().version();

    var saved = registry.change(code, room -> {});

    assertThat(saved.changed()).isFalse();
    assertThat(registry.find(code).orElseThrow().version()).isEqualTo(version);
  }

  @Test
  void aChangeTheRulesRefuseSavesNothing() {
    var code = registry.create("ana").room().code();

    assertThatThrownBy(
            () ->
                registry.change(
                    code,
                    room -> {
                      room.join("bob");
                      throw new IllegalMove("no");
                    }))
        .isInstanceOf(IllegalMove.class);

    var room = registry.find(code).orElseThrow();
    assertThat(room.viewFor(room.state().players().get(0).id()).players()).hasSize(1);
  }

  @Test
  void aChangeOfARoomThatDoesNotExistIsRefused() {
    assertThatThrownBy(() -> registry.change("ZZZZZ", room -> {}))
        .isInstanceOf(IllegalMove.class)
        .hasMessage("no such room");
  }

  @Test
  void aConflictIsReplayedOnTheNewerRoomSoNoChangeIsLost() {
    var code = registry.create("ana").room().code();
    var attempts = new AtomicInteger();

    registry.change(
        code,
        room -> {
          room.join("bob");
          // Someone else saves a change after this room was loaded and before it is saved.
          if (attempts.incrementAndGet() == 1) {
            registry.change(code, other -> other.join("cleo"));
          }
        });

    assertThat(attempts).hasValue(2);
    var players = registry.find(code).orElseThrow().state().players();
    assertThat(players)
        .extracting(RoomState.PlayerState::name)
        .containsExactly("ana", "cleo", "bob");
  }

  @Test
  void aRoomThatKeepsChangingUnderACommandAnswersTryAgain() {
    var code = registry.create("ana").room().code();
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                registry.change(
                    code,
                    room -> {
                      room.join("bob");
                      attempts.incrementAndGet();
                      registry.change(code, other -> other.join("cleo"));
                    }))
        .isInstanceOf(Unavailable.class)
        .hasMessage("the server is busy, try again");
    assertThat(attempts).hasValue(5);
  }

  @Test
  void theSweepDropsTheRoomsNobodyIsConnectedToAfterThirtyMinutes() {
    var kept = registry.create("ana");
    var left = registry.create("bob");
    registry.change(left.room().code(), room -> room.disconnect(left.result().getId()));

    registry.dropAbandoned();
    assertThat(registry.codes()).hasSize(2);
    clock.advance(Duration.ofMinutes(31));
    registry.dropAbandoned();

    assertThat(registry.find(kept.room().code())).isPresent();
    assertThat(registry.find(left.room().code())).isEmpty();
  }
}
