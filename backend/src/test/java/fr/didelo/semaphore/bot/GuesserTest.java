package fr.didelo.semaphore.bot;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.bot.ClueGiver.Clue;
import fr.didelo.semaphore.game.WordList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GuesserTest {

  private static Guesser guesser;

  @BeforeAll
  static void load() throws IOException {
    guesser = Guesser.of(new BotConfig().wordVectors());
  }

  @Test
  void itPicksTheCardsTheClueIsClosestTo() {
    var hidden = List.of("avion", "chien", "citron", "bouteille", "chat", "aéroport");

    for (var seed = 0; seed < 20; seed++) {
      var random = new SplittableRandom(seed);

      assertThat(guesser.picks(hidden, new Clue("animal", 1), Level.HARD, random).getFirst())
          .isIn("chien", "chat");
      assertThat(guesser.picks(hidden, new Clue("animal", 2), Level.HARD, random).subList(0, 2))
          .containsExactlyInAnyOrder("chien", "chat");
    }
  }

  @ParameterizedTest
  @EnumSource(Level.class)
  void itPicksHiddenCardsOnceEachAndAtMostOneBeyondTheNumber(Level level) {
    var words = new ArrayList<>(new WordList().words());
    for (var seed = 0; seed < 20; seed++) {
      Collections.shuffle(words, new SplittableRandom(seed));
      var hidden = words.subList(0, 16);
      var number = seed % 4 + 1;

      var picks =
          guesser.picks(hidden, new Clue("voyage", number), level, new SplittableRandom(seed));

      assertThat(picks)
          .isSubsetOf(hidden)
          .doesNotHaveDuplicates()
          .hasSizeBetween(number, number + 1);
    }
  }

  @Test
  void aClueTheVectorsDoNotKnowIsAPass() {
    assertThat(
            guesser.picks(
                List.of("chat", "chien"),
                new Clue("zzxqw", 1),
                Level.HARD,
                new SplittableRandom(1)))
        .isEmpty();
  }

  @Test
  void aClueIsFoundWhateverItsAccentsAndCase() {
    var hidden = List.of("soleil", "neige", "plage", "hiver", "chien", "avion");

    var picks = guesser.picks(hidden, new Clue("été", 2), Level.HARD, new SplittableRandom(4));

    assertThat(guesser.picks(hidden, new Clue("Ete", 2), Level.HARD, new SplittableRandom(4)))
        .isEqualTo(picks);
    assertThat(guesser.picks(hidden, new Clue("ÉTÉ", 2), Level.HARD, new SplittableRandom(4)))
        .isEqualTo(picks);
  }

  @Test
  void aSeedFixesThePicksAndTheNoiseOfALowLevelChangesThem() {
    var hidden = List.of("avion", "chien", "citron", "bouteille", "chat", "aéroport", "roi");
    var clue = new Clue("animal", 1);

    var picks =
        java.util.stream.IntStream.range(0, 30)
            .mapToObj(seed -> guesser.picks(hidden, clue, Level.EASY, new SplittableRandom(seed)))
            .toList();

    assertThat(guesser.picks(hidden, clue, Level.EASY, new SplittableRandom(5)))
        .isEqualTo(picks.get(5));
    assertThat(picks.stream().distinct()).hasSizeGreaterThan(1);
  }

  /** The vectors hold every word of the game, so a guesser never meets one it cannot read. */
  @Test
  void itReadsEveryWordOfTheGame() {
    var words = new WordList().words();

    assertThat(guesser.picks(words, new Clue("mer", 3), Level.NORMAL, new SplittableRandom(0)))
        .isSubsetOf(words);
  }
}
