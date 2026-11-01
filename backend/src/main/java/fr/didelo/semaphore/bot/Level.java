package fr.didelo.semaphore.bot;

import fr.didelo.semaphore.game.Room;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How well a clue-giver plays (docs/adr/0014-a-clue-giver-without-a-language-model.md): how many
 * words one clue may count, by how much the words it counts must lead the dangerous ones, and how
 * many of the best clues it draws from.
 */
@Getter
@RequiredArgsConstructor
public enum Level {
  EASY(2, 0.0, Integer.MAX_VALUE),
  NORMAL(3, 0.02, 5),
  HARD(Room.MAX_NUMBER, 0.03, 1);

  private final int maxNumber;
  private final double margin;
  private final int pool;
}
