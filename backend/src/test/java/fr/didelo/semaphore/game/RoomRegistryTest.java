package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RoomRegistryTest {

  private final MutableClock clock = new MutableClock();
  private final RoomRegistry registry = new RoomRegistry(clock, new WordList(), 2);

  @Test
  void aCodeHasFiveCharactersWithoutLookAlikes() {
    var codes = IntStream.range(0, 200).mapToObj(i -> registry.create().code()).toList();

    assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("[A-HJKMN-Z2-9]{5}"));
  }

  @Test
  void aRoomIsFoundWhateverTheCaseOrTheSpaces() {
    var code = registry.create().code();

    assertThat(registry.find(" " + code.toLowerCase() + " ")).isPresent();
    assertThat(registry.find("00000")).isEmpty();
  }

  @Test
  void theSweepDropsTheRoomsNobodyIsConnectedToAfterThirtyMinutes() {
    var kept = registry.create();
    var keeper = kept.join("ana");
    var left = registry.create();
    var leaver = left.join("bob");
    left.setConnected(leaver.getId(), false);
    var neverJoined = registry.create();

    registry.dropAbandoned();
    assertThat(registry.size()).isEqualTo(3);
    clock.advance(Duration.ofMinutes(31));
    registry.dropAbandoned();

    assertThat(registry.find(kept.code())).isPresent();
    assertThat(keeper.isConnected()).isTrue();
    assertThat(registry.find(left.code())).isEmpty();
    assertThat(registry.find(neverJoined.code())).isEmpty();
  }
}
