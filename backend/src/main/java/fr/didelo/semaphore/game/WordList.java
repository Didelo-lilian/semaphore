package fr.didelo.semaphore.game;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** The words a grid is drawn from: a short list of common French nouns, written for this game. */
@Component
public class WordList {

  private final List<String> words;

  public WordList() {
    try (var in = new ClassPathResource("words.txt").getInputStream()) {
      words = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public List<String> words() {
    return words;
  }
}
