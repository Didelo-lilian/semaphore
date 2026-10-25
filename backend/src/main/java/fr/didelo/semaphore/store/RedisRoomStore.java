package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.RoomState;
import fr.didelo.semaphore.game.RoomStore;
import fr.didelo.semaphore.game.Unavailable;
import io.lettuce.core.ScriptOutputType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * A room is a hash {@code room:{code}} with its version {@code v} and its {@code state}, the JSON
 * of a {@link RoomState}. The scripts compare the version and store the text they are given: they
 * never decode it, since Lua's cjson turns every empty array into an empty object.
 */
@Component
@WithRedis
class RedisRoomStore implements RoomStore {

  private static final String PREFIX = "room:";
  // A safety net for rooms nobody sweeps; the sweep drops an abandoned room long before.
  private static final long TTL_SECONDS = Duration.ofHours(24).toSeconds();

  private static final String CREATE =
      """
      if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end
      redis.call('HSET', KEYS[1], 'v', ARGV[1], 'state', ARGV[2])
      redis.call('EXPIRE', KEYS[1], ARGV[3])
      return 1
      """;
  private static final String REPLACE =
      """
      if redis.call('HGET', KEYS[1], 'v') ~= ARGV[1] then return 0 end
      redis.call('HSET', KEYS[1], 'v', ARGV[2], 'state', ARGV[3])
      redis.call('EXPIRE', KEYS[1], ARGV[4])
      return 1
      """;
  private static final String DROP =
      """
      if redis.call('HGET', KEYS[1], 'v') ~= ARGV[1] then return 0 end
      redis.call('DEL', KEYS[1])
      return 1
      """;

  private final RedisLink link;
  private final JsonMapper json;

  RedisRoomStore(RedisLink link, JsonMapper json) {
    this.link = link;
    // Another version of the backend may have written a field this one does not know.
    this.json = json.rebuild().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  @Override
  public Optional<RoomState> find(String code) {
    return Optional.ofNullable(link.run(commands -> commands.hget(PREFIX + code, "state")))
        .map(
            state -> {
              try {
                return json.readValue(state, RoomState.class);
              } catch (JacksonException e) {
                // Written by a version that this one cannot read, mid-deploy: as if Redis was busy.
                throw new Unavailable(RedisLink.BUSY, e);
              }
            });
  }

  @Override
  public boolean create(RoomState room) {
    return script(
        CREATE,
        PREFIX + room.code(),
        String.valueOf(room.version()),
        json.writeValueAsString(room),
        String.valueOf(TTL_SECONDS));
  }

  @Override
  public boolean replace(RoomState room, int loadedVersion) {
    return script(
        REPLACE,
        PREFIX + room.code(),
        String.valueOf(loadedVersion),
        String.valueOf(room.version()),
        json.writeValueAsString(room),
        String.valueOf(TTL_SECONDS));
  }

  @Override
  public List<String> codes() {
    return List.copyOf(link.keys(PREFIX));
  }

  @Override
  public boolean drop(String code, int version) {
    return script(DROP, PREFIX + code, String.valueOf(version));
  }

  private boolean script(String script, String key, String... arguments) {
    Long done =
        link.run(
            commands ->
                commands.eval(script, ScriptOutputType.INTEGER, new String[] {key}, arguments));
    return done == 1;
  }
}
