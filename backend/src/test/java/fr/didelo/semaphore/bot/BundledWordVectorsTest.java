package fr.didelo.semaphore.bot;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.bot.WordVectors.Neighbour;
import fr.didelo.semaphore.game.WordList;
import java.io.IOException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The file that ships with the backend, read the way the application reads it. */
class BundledWordVectorsTest {

  private final WordVectors vectors = bundled();

  private static WordVectors bundled() {
    try {
      return new BotConfig().wordVectors();
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  @Test
  void itHoldsTheVocabularyOfTheAdr() {
    assertThat(vectors.size()).isEqualTo(50_001);
    assertThat(vectors.dimensions()).isEqualTo(200);
    assertThat(IntStream.range(0, vectors.size()).mapToObj(vectors::word))
        .allMatch(word -> word.matches("[a-zàâäçéèêëîïôöùûüÿœæ]{3,}"))
        .doesNotHaveDuplicates();
  }

  @Test
  void everyWordOfTheGameIsKnown() {
    assertThat(new WordList().words())
        .allSatisfy(word -> assertThat(vectors.rank(word)).as(word).isPresent());
  }

  @Test
  void theRanksFollowTheFrequencyOrder() {
    assertThat(vectors.rank("pour").getAsInt()).isLessThan(vectors.rank("chat").getAsInt());
    assertThat(vectors.rank("chat").getAsInt()).isLessThan(vectors.rank("râteau").getAsInt());
  }

  @Test
  void closeWordsAreCloserThanFarOnes() {
    assertThat(vectors.similarity("chat", "chien"))
        .isGreaterThan(vectors.similarity("chat", "avion"));
    assertThat(vectors.similarity("roi", "reine"))
        .isGreaterThan(vectors.similarity("roi", "bouteille"));
    assertThat(vectors.similarity("avion", "aéroport"))
        .isGreaterThan(vectors.similarity("avion", "citron"));
  }

  @Test
  void theNeighboursOfAWordMakeSense() {
    var neighbours = vectors.nearest("chat", 10);

    assertThat(neighbours).hasSize(10).extracting(Neighbour::word).contains("chats", "chien");
    assertThat(neighbours)
        .extracting(Neighbour::similarity)
        .isSortedAccordingTo(java.util.Comparator.reverseOrder());
  }
}
