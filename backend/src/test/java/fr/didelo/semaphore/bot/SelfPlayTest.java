package fr.didelo.semaphore.bot;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.game.Team;
import java.io.IOException;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SelfPlayTest {

  private static final int GAMES = 200;

  private static ClueGiver giver;
  private static Guesser guesser;

  @BeforeAll
  static void load() throws IOException {
    var vectors = new BotConfig().wordVectors();
    giver = ClueGiver.of(vectors);
    guesser = Guesser.of(vectors);
  }

  /**
   * The share of {@value #GAMES} games a team of this level wins against a team of normal level.
   */
  private static double winRate(Level level) {
    return IntStream.range(0, GAMES)
            .parallel()
            .filter(
                seed ->
                    SelfPlay.play(
                            giver,
                            guesser,
                            Map.of(Team.BABORD, level, Team.TRIBORD, Level.NORMAL),
                            seed % 2 == 0 ? Team.BABORD : Team.TRIBORD,
                            seed)
                        == Team.BABORD)
            .count()
        / (double) GAMES;
  }

  @Test
  void theWinRateRisesFromEasyToNormalToHard() {
    var easy = winRate(Level.EASY);
    var normal = winRate(Level.NORMAL);
    var hard = winRate(Level.HARD);

    // Normal against normal says whether the games are fair to the team that starts.
    assertThat(normal).isBetween(0.4, 0.6);
    assertThat(easy).isLessThan(normal);
    assertThat(normal).isLessThan(hard);
  }
}
