package fr.didelo.semaphore.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import fr.didelo.semaphore.bot.WordVectors.Neighbour;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WordVectorsTest {

  private static final int FRV1 = 'F' << 24 | 'R' << 16 | 'V' << 8 | '1';

  /** Three words in two dimensions: chat and chien point the same way, avion is at right angles. */
  private static byte[] file(int magic, boolean trailingByte, boolean truncated)
      throws IOException {
    var bytes = new ByteArrayOutputStream();
    var out = new DataOutputStream(bytes);
    out.writeInt(magic);
    out.writeInt(3);
    out.writeInt(2);
    for (var word : new String[] {"chat", "chien", "avion"}) {
      var utf8 = word.getBytes(StandardCharsets.UTF_8);
      out.writeByte(utf8.length);
      out.write(utf8);
    }
    out.write(new byte[] {100, 0, 90, 43, 0, 100});
    if (trailingByte) {
      out.writeByte(1);
    }
    var all = bytes.toByteArray();
    return truncated ? java.util.Arrays.copyOf(all, all.length - 1) : all;
  }

  private static WordVectors read(byte[] file) throws IOException {
    return WordVectors.load(new ByteArrayInputStream(file));
  }

  @Test
  void aWordIsItsRankInTheFile() throws IOException {
    var vectors = read(file(FRV1, false, false));

    assertThat(vectors.size()).isEqualTo(3);
    assertThat(vectors.dimensions()).isEqualTo(2);
    assertThat(vectors.word(1)).isEqualTo("chien");
    assertThat(vectors.rank("avion")).hasValue(2);
    assertThat(vectors.rank("bateau")).isEmpty();
  }

  @Test
  void similarityIsTheCosine() throws IOException {
    var vectors = read(file(FRV1, false, false));

    assertThat(vectors.similarity("chat", "chat")).isCloseTo(1.0, within(1e-9));
    assertThat(vectors.similarity("chat", "chien"))
        .isCloseTo(100 * 90 / (100.0 * Math.hypot(90, 43)), within(1e-9));
    assertThat(vectors.similarity("chat", "avion")).isZero();
    assertThat(vectors.similarity("chien", "chat")).isEqualTo(vectors.similarity("chat", "chien"));
  }

  @Test
  void theNearestComeClosestFirstAndNeverIncludeTheWordItself() throws IOException {
    var vectors = read(file(FRV1, false, false));

    assertThat(vectors.nearest("chat", 1)).extracting(Neighbour::word).containsExactly("chien");
    assertThat(vectors.nearest("chat", 10))
        .extracting(Neighbour::word)
        .containsExactly("chien", "avion");
  }

  @Test
  void anUnknownWordIsRefused() throws IOException {
    var vectors = read(file(FRV1, false, false));

    assertThatThrownBy(() -> vectors.similarity("chat", "bateau"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown word: bateau");
    assertThatThrownBy(() -> vectors.nearest("bateau", 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFileOfAnotherFormatOrCutShortOrWithLeftoversIsRefused() {
    assertThatThrownBy(() -> read(file(FRV1 + 1, false, false)))
        .isInstanceOf(IOException.class)
        .hasMessage("not a FRV1 word vector file");
    assertThatThrownBy(() -> read(file(FRV1, false, true))).isInstanceOf(IOException.class);
    assertThatThrownBy(() -> read(file(FRV1, true, false)))
        .isInstanceOf(IOException.class)
        .hasMessage("bytes after the last vector");
  }
}
