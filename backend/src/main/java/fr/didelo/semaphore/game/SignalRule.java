package fr.didelo.semaphore.game;

import java.text.Normalizer;
import java.util.Locale;

/**
 * What the server refuses as a signal (docs/adr/0001-name-rules-and-visual-direction.md): the rule
 * is here so that the bots give signals the server accepts.
 */
public final class SignalRule {

  private static final int MAX_LENGTH = 30;

  private SignalRule() {}

  /** Lowercase letters without accents, which is what two words are compared by. */
  public static String normalize(String text) {
    // œ and æ do not decompose into letters, so they are spelled out.
    return Normalizer.normalize(text.strip(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replace("œ", "oe")
        .replace("æ", "ae");
  }

  /** One word, letters only, of a length the table can show; the text is already normalised. */
  public static boolean isWord(String signal) {
    return signal.matches("[a-z]+(-[a-z]+)*") && signal.length() <= MAX_LENGTH;
  }

  /**
   * Whether a signal is a word of the grid, contains one, is contained in one or starts like one
   * (the first four letters); both are already normalised.
   */
  public static boolean tooClose(String signal, String word) {
    var sharesStart =
        signal.length() >= 4
            && word.length() >= 4
            && signal.substring(0, 4).equals(word.substring(0, 4));
    var contains =
        (word.length() >= 3 && signal.contains(word))
            || (signal.length() >= 3 && word.contains(signal));
    return signal.equals(word) || contains || sharesStart;
  }
}
