package fr.didelo.semaphore.game;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every room, in memory. A room nobody is connected to is dropped after a while. */
@Component
public class RoomRegistry {

  // No 0, O, 1, I or L: a code is read aloud and typed by hand.
  private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  private static final int CODE_LENGTH = 5;
  private static final Duration ABANDONED_AFTER = Duration.ofMinutes(30);

  private final ConcurrentMap<String, Room> rooms = new ConcurrentHashMap<>();
  private final SecureRandom random = new SecureRandom();
  private final Clock clock;
  private final WordList wordList;
  private final int minTeamSize;

  public RoomRegistry(
      Clock clock, WordList wordList, @Value("${semaphore.min-team-size}") int minTeamSize) {
    this.clock = clock;
    this.wordList = wordList;
    this.minTeamSize = minTeamSize;
  }

  public Room create() {
    while (true) {
      var code = new StringBuilder();
      for (var i = 0; i < CODE_LENGTH; i++) {
        code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
      }
      var room = new Room(code.toString(), clock, random, minTeamSize, wordList.words());
      if (rooms.putIfAbsent(room.code(), room) == null) {
        return room;
      }
    }
  }

  public Optional<Room> find(String code) {
    return Optional.ofNullable(rooms.get(code.strip().toUpperCase(java.util.Locale.ROOT)));
  }

  @Scheduled(fixedDelay = 60_000)
  void dropAbandoned() {
    rooms
        .values()
        .removeIf(
            room -> {
              synchronized (room) {
                return room.abandonedFor(ABANDONED_AFTER);
              }
            });
  }

  int size() {
    return rooms.size();
  }
}
