package fr.didelo.semaphore.bot;

import fr.didelo.semaphore.game.Room;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How well a bot plays. As a Signaleur (docs/adr/0014-a-clue-giver-without-a-language-model.md):
 * how many words one clue may count, by how much the words it counts must lead the dangerous ones,
 * and how many of the best clues it draws from. As a lookout
 * (docs/adr/0015-a-guesser-without-a-language-model.md): how blurred its reading of a clue is and
 * how far behind the best card it still picks.
 */
@Getter
@RequiredArgsConstructor
public enum Level {
  EASY(2, 0.0, Integer.MAX_VALUE, 0.10, 1.0),
  NORMAL(3, 0.02, 5, 0.07, 0.15),
  HARD(Room.MAX_NUMBER, 0.03, 1, 0.03, 0.05);

  private final int maxNumber;
  private final double margin;
  private final int pool;
  private final double noise;
  private final double drop;
}
