package fr.didelo.semaphore.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.Valkey;
import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.Unavailable;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The store contract against a real Valkey, and what only Redis can get wrong. */
class RedisRoomStoreIT extends RoomStoreContract {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static RedisLink link;
  private static RedisRoomStore store;
  private static RedisClient admin;

  @BeforeAll
  static void connect() {
    link = new RedisLink(Valkey.url());
    store = new RedisRoomStore(link, JSON);
    admin = RedisClient.create(Valkey.url());
  }

  @AfterAll
  static void disconnect() {
    admin.shutdown();
    link.close();
  }

  @Override
  RedisRoomStore store() {
    return store;
  }

  @Test
  void aRoomThatAnotherVersionWroteWithAFieldWeDoNotKnowIsStillRead() {
    var room = lobby(newCode());
    store.create(room.state());
    try (var connection = admin.connect()) {
      var written = JSON.readTree(connection.sync().hget("room:" + room.code(), "state"));
      var withMore = ((tools.jackson.databind.node.ObjectNode) written).put("mood", "calm");
      connection.sync().hset("room:" + room.code(), "state", JSON.writeValueAsString(withMore));
    }

    assertThat(store.find(room.code())).contains(room.state());
  }

  @Test
  void aRoomExpiresAfterADayAndEverySaveRenewsIt() {
    var room = lobby(newCode());
    var loaded = room.version();
    store.create(room.state());
    try (var connection = admin.connect()) {
      connection.sync().expire("room:" + room.code(), 100);
      room.join("bob", "replica-1");

      store.replace(room.state(), loaded);

      assertThat(connection.sync().ttl("room:" + room.code())).isBetween(86_000L, 86_400L);
    }
  }

  @Test
  void aWriteThatRedisRefusesForMemoryIsUnavailableAndALoadStillWorks() {
    var kept = lobby(newCode());
    store.create(kept.state());
    var refused = lobby(newCode());
    try (var connection = admin.connect()) {
      connection.sync().configSet("maxmemory", "1");
      try {
        assertThatThrownBy(() -> store.create(refused.state()))
            .isInstanceOf(Unavailable.class)
            .hasMessage(RedisLink.FULL);
        assertThat(store.find(kept.code())).contains(kept.state());
      } finally {
        connection.sync().configSet("maxmemory", "64mb");
      }
    }
    assertThat(store.create(refused.state())).isTrue();
  }

  @Test
  void aRoomStateIsStoredAsTheJsonOfTheRecord() {
    var room = lobby(newCode());
    store.create(room.state());
    try (var connection = admin.connect()) {
      assertThat(connection.sync().hget("room:" + room.code(), "v")).isEqualTo("" + room.version());
      assertThat(
              JSON.readValue(
                  connection.sync().hget("room:" + room.code(), "state"), RoomState.class))
          .isEqualTo(room.state());
    }
  }

  @Test
  void aRoomThatThisVersionCannotReadIsUnavailableNotAnUnreadableMessage() {
    var room = lobby(newCode());
    store.create(room.state());
    try (var connection = admin.connect()) {
      var state = connection.sync().hget("room:" + room.code(), "state");
      connection.sync().hset("room:" + room.code(), "state", state.replace("LOBBY", "HARBOUR"));
    }

    assertThatThrownBy(() -> store.find(room.code()))
        .isInstanceOf(Unavailable.class)
        .hasMessage(RedisLink.BUSY);
  }
}
