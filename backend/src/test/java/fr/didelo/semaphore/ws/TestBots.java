package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.bot.ClueGiver;
import fr.didelo.semaphore.bot.Guesser;
import fr.didelo.semaphore.bot.WordVectors;
import fr.didelo.semaphore.game.RoomRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;

/** The bots of the tests: the vectors that ship with the backend, read once. */
final class TestBots {

  private static final ClueGiver CLUE_GIVER;
  private static final Guesser GUESSER;

  static {
    try (var in = TestBots.class.getResourceAsStream("/bot/fr-vectors.bin")) {
      var vectors = WordVectors.load(in);
      CLUE_GIVER = ClueGiver.of(vectors);
      GUESSER = Guesser.of(vectors);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private TestBots() {}

  static BotTurns turns(RoomRegistry rooms, GameSocketHandler handler, Duration pace) {
    return new BotTurns(rooms, handler, CLUE_GIVER, GUESSER, pace, pace);
  }
}
