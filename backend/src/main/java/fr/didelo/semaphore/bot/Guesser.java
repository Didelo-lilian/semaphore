package fr.didelo.semaphore.bot;

import fr.didelo.semaphore.bot.ClueGiver.Clue;
import fr.didelo.semaphore.game.SignalRule;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Picks cards from a signal (docs/adr/0015-a-guesser-without-a-language-model.md). It is given the
 * words still hidden and nothing else: the colour of a hidden card is not part of what a lookout
 * sees, so it is not part of what this takes.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class Guesser {

  private final WordVectors vectors;
  // The words of the vectors as the server compares them, so that "ete" is "été"; the most frequent
  // spelling of two that collide.
  private final Map<String, Integer> ranks;

  public static Guesser of(WordVectors vectors) {
    var ranks = HashMap.<String, Integer>newHashMap(vectors.size());
    for (var rank = 0; rank < vectors.size(); rank++) {
      ranks.putIfAbsent(SignalRule.normalize(vectors.word(rank)), rank);
    }
    return new Guesser(vectors, ranks);
  }

  /**
   * The hidden words to pick, the likeliest first: as many as the number, which the Signaleur
   * vouches for, and one more, the way the game allows, only if that card reads about as close as
   * the last one (within the level's {@code drop}). A signal the vectors do not know gives nothing
   * to go by: no pick, which is a pass.
   */
  public List<String> picks(List<String> hidden, Clue clue, Level level, RandomGenerator random) {
    var rank = ranks.get(SignalRule.normalize(clue.word()));
    if (rank == null) {
      return List.of();
    }
    var read =
        hidden.stream()
            .map(
                word ->
                    Map.entry(
                        word,
                        vectors.similarity(rank, vectors.rankOf(word))
                            + level.getNoise() * random.nextGaussian()))
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .toList();
    var count = Math.min(clue.number(), read.size());
    if (count < read.size()
        && read.get(count).getValue() >= read.get(count - 1).getValue() - level.getDrop()) {
      count++;
    }
    return read.subList(0, count).stream().map(Map.Entry::getKey).toList();
  }
}
