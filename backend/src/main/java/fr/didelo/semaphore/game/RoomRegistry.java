package fr.didelo.semaphore.game;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Every room, through a {@link RoomStore}. A change loads the room, applies a command in Java and
 * saves the result if nobody saved a newer one meanwhile, else starts again from the newer one. A
 * room none of whose players is connected is dropped after a while.
 */
@Component
public class RoomRegistry {

  // No 0, O, 1, I or L: a code is read aloud and typed by hand.
  private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 5;
  private static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);
  // Twelve players on one room make conflicts rare and cheap: a few tries, then "try again".
  private static final int ATTEMPTS = 5;

  /** The room as it was saved, what the change returned and whether the change did anything. */
  public record Saved<R>(Room room, R result, boolean changed) {}

  private final SecureRandom random = new SecureRandom();
  private final RoomStore store;
  private final Clock clock;
  private final WordList wordList;
  private final int minTeamSize;

  public RoomRegistry(
      RoomStore store,
      Clock clock,
      WordList wordList,
      @Value("${semaphore.min-team-size}") int minTeamSize) {
    this.store = store;
    this.clock = clock;
    this.wordList = wordList;
    this.minTeamSize = minTeamSize;
  }

  /**
   * A new room with its first player, who is its host and whose connection {@code replica} holds: a
   * bad name leaves nothing behind.
   */
  public Saved<Player> create(String name, String replica) {
    while (true) {
      var code = new StringBuilder();
      for (var i = 0; i < CODE_LENGTH; i++) {
        code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
      }
      var room = new Room(code.toString(), clock, random, minTeamSize, wordList.words());
      var host = room.join(name, replica);
      if (store.create(room.state())) {
        return new Saved<>(room, host, true);
      }
    }
  }

  public Optional<Room> find(String code) {
    return store
        .find(code.strip().toUpperCase(Locale.ROOT))
        .map(state -> Room.restore(state, clock, random, minTeamSize, wordList.words()));
  }

  public List<String> codes() {
    return store.codes();
  }

  /**
   * Applies {@code change} to the room and saves it. It may run more than once, each time on a
   * fresh room, so it must not do anything but change that room. Whatever it throws leaves the room
   * as it was.
   */
  public <R> Saved<R> update(String code, Function<Room, R> change) {
    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      var room = find(code).orElseThrow(() -> new IllegalMove("no such room"));
      var loaded = room.version();
      var result = change.apply(room);
      if (room.version() == loaded || store.replace(room.state(), loaded)) {
        return new Saved<>(room, result, room.version() != loaded);
      }
    }
    throw new Unavailable("the server is busy, try again");
  }

  public Saved<Void> change(String code, Consumer<Room> change) {
    return update(
        code,
        room -> {
          change.accept(room);
          return null;
        });
  }

  public void dropAbandoned() {
    for (var code : store.codes()) {
      try {
        find(code)
            .filter(room -> room.abandonedFor(ABANDONED_AFTER))
            .ifPresent(room -> store.drop(code, room.version()));
      } catch (Unavailable e) {
        // This room cannot be read now: the next sweep tries again, and the others go on.
      } catch (RuntimeException e) {
        RoomFailures.log(code, "dropped", e);
      }
    }
  }
}
