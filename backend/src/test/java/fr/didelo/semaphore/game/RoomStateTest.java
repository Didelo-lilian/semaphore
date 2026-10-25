package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class RoomStateTest {

  private static final JsonMapper JSON =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  @Test
  void aRoomSavedBeforeACollectionExistedReadsWithItEmptyAndStillRestores() {
    var state =
        JSON.readValue(
            "{\"code\":\"ABCDE\",\"version\":3,\"phase\":\"LOBBY\",\"revealsLeft\":0}",
            RoomState.class);

    assertThat(state.flags()).isEmpty();
    assertThat(state.board()).isEmpty();
    assertThat(state.players()).isEmpty();
    assertThat(state.spectators()).isEmpty();
    var room = Room.restore(state, new MutableClock(), new Random(1), 2, List.of("voile", "ancre"));
    assertThat(room.code()).isEqualTo("ABCDE");
  }
}
