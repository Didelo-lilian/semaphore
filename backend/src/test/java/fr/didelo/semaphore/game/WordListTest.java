package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WordListTest {

  private final java.util.List<String> words = new WordList().words();

  @Test
  void theListHoldsEnoughCleanDistinctWords() {
    var normalized = words.stream().map(Room::normalize).toList();

    assertThat(words)
        .hasSizeGreaterThanOrEqualTo(Room.BOARD_SIZE)
        .allMatch(w -> w.equals(w.strip()));
    assertThat(normalized).doesNotHaveDuplicates().allMatch(w -> w.matches("[a-z]+(-[a-z]+)*"));
  }

  /** Two close words on one grid would make each other impossible to signal. */
  @Test
  void noWordIsTooCloseToAnother() {
    var normalized = words.stream().map(Room::normalize).toList();

    IntStream.range(0, normalized.size())
        .forEach(
            i ->
                IntStream.range(i + 1, normalized.size())
                    .forEach(
                        j -> {
                          var a = normalized.get(i);
                          var b = normalized.get(j);
                          assertThat(a.contains(b) || b.contains(a))
                              .as("%s and %s", a, b)
                              .isFalse();
                          assertThat(
                                  a.length() >= 4
                                      && b.length() >= 4
                                      && a.substring(0, 4).equals(b.substring(0, 4)))
                              .as("%s and %s share their first four letters", a, b)
                              .isFalse();
                        }));
  }
}
