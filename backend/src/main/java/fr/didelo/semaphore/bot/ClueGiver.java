package fr.didelo.semaphore.bot;

import fr.didelo.semaphore.game.Card;
import fr.didelo.semaphore.game.CardKind;
import fr.didelo.semaphore.game.SignalRule;
import fr.didelo.semaphore.game.Team;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * Gives a signal: a word close to some words of its team and far from the others
 * (docs/adr/0014-a-clue-giver-without-a-language-model.md). It sees the grid as the Signaleur does.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class ClueGiver {

  public record Clue(String word, int number) {}

  /** A candidate, the number of words it counts and how far they lead the dangerous ones. */
  private record Scored(int candidate, int number, double score) {}

  // The most frequent words are the function words and the light verbs; the rarest are names and
  // typos.
  private static final int FIRST_RANK = 400;
  private static final int LAST_RANK = 12_000;
  // A reef costs a flag of two and ends the turn: it counts as closer to the clue than it is.
  private static final double REEF_MARGIN = 0.05;

  private final WordVectors vectors;
  // The ranks that can be a signal, and the same words as the server compares them.
  private final int[] candidates;
  private final String[] normalized;

  public static ClueGiver of(WordVectors vectors) {
    var candidates =
        IntStream.range(FIRST_RANK, Math.min(LAST_RANK, vectors.size()))
            .filter(rank -> SignalRule.isWord(SignalRule.normalize(vectors.word(rank))))
            .toArray();
    var normalized =
        Arrays.stream(candidates)
            .mapToObj(rank -> SignalRule.normalize(vectors.word(rank)))
            .toArray(String[]::new);
    return new ClueGiver(vectors, candidates, normalized);
  }

  /**
   * The clue of {@code team}, whose words and the others' are those of the cards not revealed yet.
   * It is neither a word of the grid, revealed or not, nor too close to one for the server.
   */
  public Clue clue(List<Card> board, Team team, Level level, RandomGenerator random) {
    var gridWords = board.stream().map(card -> SignalRule.normalize(card.word())).toList();
    var allowed =
        IntStream.range(0, candidates.length)
            .filter(i -> gridWords.stream().noneMatch(w -> SignalRule.tooClose(normalized[i], w)))
            .toArray();
    var own = ranks(board, card -> card.kind() == team.kind());
    var others =
        ranks(board, card -> card.kind() == team.other().kind() || card.kind() == CardKind.CALM);
    var reefs = ranks(board, card -> card.kind() == CardKind.REEF);

    var pool = level.getPool();
    var scored = scored(allowed, own, others, reefs, level.getMaxNumber(), level.getMargin());
    if (scored.isEmpty()) {
      // No clue leads the dangerous words: the one that comes closest to leading them, for one
      // word.
      scored = scored(allowed, own, others, reefs, 1, Double.NEGATIVE_INFINITY);
      pool = 1;
    }
    scored.sort(Comparator.comparingDouble(Scored::score).reversed());
    var chosen = scored.get(random.nextInt(Math.min(pool, scored.size())));
    return new Clue(vectors.word(candidates[chosen.candidate()]), chosen.number());
  }

  /**
   * The candidates that count at least one word of the team: from the closest, each word leading
   * the dangerous ones by {@code margin}, {@code maxNumber} words at most.
   */
  private List<Scored> scored(
      int[] allowed, int[] own, int[] others, int[] reefs, int maxNumber, double margin) {
    var result = new ArrayList<Scored>();
    var similarities = new double[own.length];
    for (var i : allowed) {
      var rank = candidates[i];
      for (var k = 0; k < own.length; k++) {
        similarities[k] = vectors.similarity(rank, own[k]);
      }
      Arrays.sort(similarities);
      var closest = similarities[own.length - 1];
      // A dangerous word as close as the closest own word ends the search: no number is left.
      var danger = Double.NEGATIVE_INFINITY;
      for (var k = 0; k < others.length && danger + margin < closest; k++) {
        danger = Math.max(danger, vectors.similarity(rank, others[k]));
      }
      for (var k = 0; k < reefs.length && danger + margin < closest; k++) {
        danger = Math.max(danger, vectors.similarity(rank, reefs[k]) + REEF_MARGIN);
      }
      var number = 0;
      var score = 0.0;
      while (number < Math.min(maxNumber, own.length)
          && similarities[own.length - 1 - number] > danger + margin) {
        score += similarities[own.length - 1 - number] - danger;
        number++;
      }
      if (number > 0) {
        result.add(new Scored(i, number, score));
      }
    }
    return result;
  }

  private int[] ranks(List<Card> board, Predicate<Card> kind) {
    return board.stream()
        .filter(card -> !card.revealed() && kind.test(card))
        .mapToInt(card -> vectors.rankOf(card.word()))
        .toArray();
  }
}
