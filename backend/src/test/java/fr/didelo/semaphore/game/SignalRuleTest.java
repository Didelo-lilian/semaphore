package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SignalRuleTest {

  @Test
  void normalizeDropsAccentsCaseAndLigatures() {
    assertThat(SignalRule.normalize("  Écœur ")).isEqualTo("ecoeur");
    assertThat(SignalRule.normalize("ÉTÉ")).isEqualTo("ete");
    assertThat(SignalRule.normalize("Cæsium")).isEqualTo("caesium");
  }

  @Test
  void aWordIsLettersWithInnerHyphensAndNotTooLong() {
    assertThat(SignalRule.isWord("chat")).isTrue();
    assertThat(SignalRule.isWord("porte-monnaie")).isTrue();
    assertThat(SignalRule.isWord("a".repeat(30))).isTrue();

    assertThat(SignalRule.isWord("a".repeat(31))).isFalse();
    assertThat(SignalRule.isWord("deux mots")).isFalse();
    assertThat(SignalRule.isWord("r2d2")).isFalse();
    assertThat(SignalRule.isWord("-chat")).isFalse();
    assertThat(SignalRule.isWord("chat-")).isFalse();
    assertThat(SignalRule.isWord("")).isFalse();
  }

  @Test
  void aSignalIsTooCloseToAWordItEqualsContainsIsContainedInOrStartsLike() {
    assertThat(SignalRule.tooClose("chat", "chat")).isTrue();
    assertThat(SignalRule.tooClose("chatte", "chat")).isTrue();
    assertThat(SignalRule.tooClose("chat", "chatte")).isTrue();
    assertThat(SignalRule.tooClose("maisonnette", "maisonnier")).isTrue();

    assertThat(SignalRule.tooClose("chat", "chien")).isFalse();
    assertThat(SignalRule.tooClose("pomme", "pompe")).isFalse();
    assertThat(SignalRule.tooClose("ab", "about")).isFalse();
  }
}
