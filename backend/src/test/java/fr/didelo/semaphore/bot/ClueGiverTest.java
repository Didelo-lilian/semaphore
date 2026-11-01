package fr.didelo.semaphore.bot;

import static org.assertj.core.api.Assertions.assertThat;

import fr.didelo.semaphore.bot.ClueGiver.Clue;
import fr.didelo.semaphore.game.Card;
import fr.didelo.semaphore.game.CardKind;
import fr.didelo.semaphore.game.SignalRule;
import fr.didelo.semaphore.game.Team;
import fr.didelo.semaphore.game.WordList;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClueGiverTest {

  private static final int BOARDS = 20;

  private static WordVectors vectors;
  private static ClueGiver giver;

  @BeforeAll
  static void load() throws IOException {
    vectors = new BotConfig().wordVectors();
    giver = ClueGiver.of(vectors);
  }

  /** A grid dealt from the word list the way a room deals it: 8, 8, 6 and 2 cards. */
  private static List<Card> board(long seed) {
    var words = new ArrayList<>(new WordList().words());
    Collections.shuffle(words, new SplittableRandom(seed));
    var kinds =
        Stream.of(
                Collections.nCopies(8, CardKind.BABORD),
                Collections.nCopies(8, CardKind.TRIBORD),
                Collections.nCopies(6, CardKind.CALM),
                Collections.nCopies(2, CardKind.REEF))
            .flatMap(List::stream)
            .toList();
    return IntStream.range(0, kinds.size())
        .mapToObj(i -> new Card(words.get(i), kinds.get(i), false))
        .toList();
  }

  @ParameterizedTest
  @EnumSource(Level.class)
  void theClueIsAWordTheServerAcceptsAndNeverOneOfTheGrid(Level level) {
    for (var seed = 0; seed < BOARDS; seed++) {
      var board = board(seed);
      var clue = giver.clue(board, Team.BABORD, level, new SplittableRandom(seed));
      var signal = SignalRule.normalize(clue.word());

      assertThat(SignalRule.isWord(signal)).as(clue.word()).isTrue();
      assertThat(board)
          .allSatisfy(
              card ->
                  assertThat(SignalRule.tooClose(signal, SignalRule.normalize(card.word())))
                      .as("%s and %s", clue.word(), card.word())
                      .isFalse());
      assertThat(clue.number()).isBetween(1, level.getMaxNumber());
    }
  }

  @Test
  void aRevealedCardIsNeitherCountedNorSignalled() {
    var board = board(7);
    var revealed = new ArrayList<Card>();
    for (var card : board) {
      var own = card.kind() == CardKind.BABORD && revealed.size() < 6;
      revealed.add(own ? new Card(card.word(), card.kind(), true) : card);
    }

    var clue = giver.clue(revealed, Team.BABORD, Level.HARD, new SplittableRandom(7));

    assertThat(clue.number()).isBetween(1, 2);
    assertThat(revealed)
        .allSatisfy(
            card ->
                assertThat(
                        SignalRule.tooClose(
                            SignalRule.normalize(clue.word()), SignalRule.normalize(card.word())))
                    .isFalse());
  }

  @Test
  void theSameSeedGivesTheSameClueAndTheLevelsDrawAmongTheirBest() {
    var board = board(3);

    var easy = IntStream.range(0, 10).mapToObj(seed -> clue(board, Level.EASY, seed)).toList();
    var hard = IntStream.range(0, 10).mapToObj(seed -> clue(board, Level.HARD, seed)).toList();

    assertThat(clue(board, Level.EASY, 5)).isEqualTo(clue(board, Level.EASY, 5));
    assertThat(easy.stream().distinct()).hasSizeGreaterThan(1);
    assertThat(hard.stream().distinct()).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(
      value = Level.class,
      names = {"NORMAL", "HARD"})
  void theWordsItCountsLeadTheDangerousOnesByTheMargin(Level level) {
    for (var seed = 0; seed < BOARDS; seed++) {
      var board = board(seed);
      var clue = giver.clue(board, Team.TRIBORD, level, new SplittableRandom(seed));
      var rank = vectors.rankOf(clue.word());
      var own =
          similarities(rank, board, card -> card.kind() == CardKind.TRIBORD)
              .boxed()
              .sorted(Comparator.reverseOrder())
              .toList();
      var danger =
          Math.max(
              similarities(
                      rank,
                      board,
                      card -> card.kind() == CardKind.BABORD || card.kind() == CardKind.CALM)
                  .max()
                  .getAsDouble(),
              similarities(rank, board, card -> card.kind() == CardKind.REEF).max().getAsDouble()
                  + 0.05);

      assertThat(own.subList(0, clue.number()))
          .as("%s for %s", clue, board)
          .allSatisfy(
              similarity -> assertThat(similarity).isGreaterThan(danger + level.getMargin()));
    }
  }

  /**
   * A guesser that reads the same vectors as the clue-giver would never miss, so its reading is
   * blurred: it finds the cards of the clue-giver's team before its first mistake.
   */
  @Test
  void aHigherLevelLeadsASimulatedGuesserToMoreOfItsCards() {
    var found = new EnumMap<Level, Double>(Level.class);
    for (var level : Level.values()) {
      found.put(
          level,
          IntStream.range(0, 100)
              .mapToDouble(
                  seed -> {
                    var board = board(seed);
                    var clue = giver.clue(board, Team.BABORD, level, new SplittableRandom(seed));
                    return foundBeforeTheFirstMistake(
                        board, Team.BABORD, clue, new SplittableRandom(~seed));
                  })
              .average()
              .getAsDouble());
    }

    assertThat(found.get(Level.EASY)).isLessThan(found.get(Level.NORMAL));
    assertThat(found.get(Level.NORMAL)).isLessThan(found.get(Level.HARD));
  }

  private static Clue clue(List<Card> board, Level level, long seed) {
    return giver.clue(board, Team.BABORD, level, new SplittableRandom(seed));
  }

  private static DoubleStream similarities(int rank, List<Card> board, Predicate<Card> kind) {
    return board.stream()
        .filter(kind)
        .mapToDouble(card -> vectors.similarity(rank, vectors.rankOf(card.word())));
  }

  private static long foundBeforeTheFirstMistake(
      List<Card> board, Team team, Clue clue, RandomGenerator random) {
    var rank = vectors.rankOf(clue.word());
    return board.stream()
        .map(
            card ->
                Map.entry(
                    card,
                    vectors.similarity(rank, vectors.rankOf(card.word()))
                        + 0.07 * random.nextGaussian()))
        .sorted(Map.Entry.<Card, Double>comparingByValue().reversed())
        .limit(clue.number())
        .map(Map.Entry::getKey)
        .takeWhile(card -> card.kind() == team.kind())
        .count();
  }
}
