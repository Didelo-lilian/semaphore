package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import fr.didelo.semaphore.store.InMemoryRoomStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class RoomRegistryTest {

  private static final String REPLICA = "replica-1";

  private final MutableClock clock = new MutableClock();
  private final InMemoryRoomStore store = spy(new InMemoryRoomStore());
  private final RoomRegistry registry =
      new RoomRegistry(store, clock, new WordList(), 2, List.of());

  @Test
  void aCodeHasFiveCharactersWithoutLookAlikes() {
    var codes =
        IntStream.range(0, 200)
            .mapToObj(i -> registry.create("ana", REPLICA).room().code())
            .toList();

    assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-HJKMN-Z2-9]{5}"));
  }

  @Test
  void aRoomIsFoundWhateverTheCaseOrTheSpaces() {
    var code = registry.create("ana", REPLICA).room().code();

    assertThat(registry.find(" " + code.toLowerCase() + " ")).isPresent();
    assertThat(registry.find("00000")).isEmpty();
  }

  @Test
  void aRoomWithABadNameIsNotCreated() {
    assertThatThrownBy(() -> registry.create("   ", REPLICA)).isInstanceOf(IllegalMove.class);

    assertThat(registry.codes()).isEmpty();
  }

  @Test
  void theHostIsTheFirstPlayerOfANewRoom() {
    var created = registry.create("ana", REPLICA);

    assertThat(created.room().viewFor(created.result().getId()).players())
        .singleElement()
        .satisfies(p -> assertThat(p.host()).isTrue());
  }

  @Test
  void aChangeIsSavedAndFoundAgain() {
    var code = registry.create("ana", REPLICA).room().code();

    var joined = registry.update(code, room -> room.join("bob", REPLICA));

    assertThat(joined.changed()).isTrue();
    assertThat(registry.find(code).orElseThrow().byToken(joined.result().getToken())).isPresent();
    assertThat(joined.room().version()).isEqualTo(registry.find(code).orElseThrow().version());
  }

  @Test
  void aChangeThatDoesNothingSavesNothing() {
    var code = registry.create("ana", REPLICA).room().code();
    var version = registry.find(code).orElseThrow().version();

    var saved = registry.change(code, room -> {});

    assertThat(saved.changed()).isFalse();
    assertThat(registry.find(code).orElseThrow().version()).isEqualTo(version);
  }

  @Test
  void aChangeTheRulesRefuseSavesNothing() {
    var code = registry.create("ana", REPLICA).room().code();

    assertThatThrownBy(
            () ->
                registry.change(
                    code,
                    room -> {
                      room.join("bob", REPLICA);
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
    var code = registry.create("ana", REPLICA).room().code();
    var attempts = new AtomicInteger();

    registry.change(
        code,
        room -> {
          room.join("bob", REPLICA);
          // Someone else saves a change after this room was loaded and before it is saved.
          if (attempts.incrementAndGet() == 1) {
            registry.change(code, other -> other.join("cleo", REPLICA));
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
    var code = registry.create("ana", REPLICA).room().code();
    var attempts = new AtomicInteger();

    assertThatThrownBy(
            () ->
                registry.change(
                    code,
                    room -> {
                      room.join("bob", REPLICA);
                      attempts.incrementAndGet();
                      registry.change(code, other -> other.join("cleo", REPLICA));
                    }))
        .isInstanceOf(Unavailable.class)
        .hasMessage("the server is busy, try again");
    assertThat(attempts).hasValue(5);
  }

  @Test
  void theSweepDropsTheRoomsNobodyIsConnectedToAfterThirtyMinutes() {
    var kept = registry.create("ana", REPLICA);
    var left = registry.create("bob", REPLICA);
    registry.change(left.room().code(), room -> room.disconnect(left.result().getId(), REPLICA));

    registry.dropAbandoned();
    assertThat(registry.codes()).hasSize(2);
    clock.advance(Duration.ofMinutes(31));
    registry.dropAbandoned();

    assertThat(registry.find(kept.room().code())).isPresent();
    assertThat(registry.find(left.room().code())).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("aRoomThatFails")
  void aRoomThatCannotBeReadDoesNotStopTheSweepOfTheOthers(RuntimeException failure) {
    var unreadable = registry.create("ana", REPLICA);
    var left = registry.create("bob", REPLICA);
    registry.change(left.room().code(), room -> room.disconnect(left.result().getId(), REPLICA));
    registry.change(
        unreadable.room().code(), room -> room.disconnect(unreadable.result().getId(), REPLICA));
    clock.advance(Duration.ofMinutes(31));
    doReturn(List.of(unreadable.room().code(), left.room().code())).when(store).codes();
    doThrow(failure).when(store).find(unreadable.room().code());

    registry.dropAbandoned();

    assertThat(registry.find(left.room().code())).isEmpty();
  }

  static Stream<RuntimeException> aRoomThatFails() {
    return Stream.of(
        new Unavailable("the server is busy, try again"),
        new NullPointerException("a field this version needs is missing"));
  }

  @Test
  void aGameThatEndsIsAnnouncedOnceAndAgainWhenItsAbandonedRoomIsDropped() {
    var ended = new ArrayList<RoomState>();
    var registry = new RoomRegistry(store, clock, new WordList(), 2, List.of(ended::add));
    var code = Moves.startedGame(registry, null);
    var players = registry.find(code).orElseThrow().state().players();
    var turn = registry.find(code).orElseThrow().state().turn();
    var away = players.get(turn.other() == Team.BABORD ? 0 : 2).id();
    registry.change(code, room -> room.disconnect(away, REPLICA));
    assertThat(ended).isEmpty();

    clock.advance(Room.SIGNALER_LIMIT);
    registry.change(code, Room::expire);
    registry.change(code, Room::expire);
    registry.change(code, room -> room.reconnect(away, REPLICA));

    assertThat(ended)
        .singleElement()
        .satisfies(state -> assertThat(state.phase()).isEqualTo(Phase.ENDED));

    registry.change(code, room -> players.forEach(p -> room.disconnect(p.id(), REPLICA)));
    clock.advance(Duration.ofMinutes(31));
    registry.dropAbandoned();

    assertThat(ended).hasSize(2).allSatisfy(state -> assertThat(state.code()).isEqualTo(code));
    assertThat(registry.find(code)).isEmpty();
  }

  @Test
  void aRoomThatNeverEndedIsDroppedWithoutAnAnnouncement() {
    var ended = new ArrayList<RoomState>();
    var registry = new RoomRegistry(store, clock, new WordList(), 2, List.of(ended::add));
    var code = Moves.startedGame(registry, null);
    var players = registry.find(code).orElseThrow().state().players();
    registry.change(code, room -> players.forEach(p -> room.disconnect(p.id(), REPLICA)));
    clock.advance(Duration.ofMinutes(31));

    registry.dropAbandoned();

    assertThat(registry.find(code)).isEmpty();
    assertThat(ended).isEmpty();
  }
}
