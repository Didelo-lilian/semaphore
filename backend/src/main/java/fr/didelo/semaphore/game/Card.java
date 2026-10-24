package fr.didelo.semaphore.game;

public record Card(String word, CardKind kind, boolean revealed) {

  Card reveal() {
    return new Card(word, kind, true);
  }
}
