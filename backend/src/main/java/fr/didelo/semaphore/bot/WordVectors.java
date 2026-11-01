package fr.didelo.semaphore.bot;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * French word vectors (docs/adr/0009-french-word-vectors-for-the-bots.md). A word is known by its
 * rank, which is its place in the frequency order of the source: the function words come first and
 * the rare words last. Two words are as close as the cosine of their vectors. Immutable once read,
 * so replicas' threads share it freely.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class WordVectors {

  /** The magic of the file, which also says its version. */
  private static final int FRV1 = 'F' << 24 | 'R' << 16 | 'V' << 8 | '1';

  public record Neighbour(String word, double similarity) {}

  private final List<String> words;
  private final Map<String, Integer> ranks;
  private final int dimensions;
  // One row of `dimensions` signed bytes per word, in rank order.
  private final byte[] vectors;
  private final double[] inverseNorms;

  /** Reads a file made by {@code tools/vectors/build.py}, and refuses any other. */
  public static WordVectors load(InputStream in) throws IOException {
    var data = new DataInputStream(new BufferedInputStream(in, 1 << 16));
    if (data.readInt() != FRV1) {
      throw new IOException("not a FRV1 word vector file");
    }
    var count = data.readInt();
    var dimensions = data.readInt();
    var words = new ArrayList<String>(count);
    var ranks = HashMap.<String, Integer>newHashMap(count);
    for (var rank = 0; rank < count; rank++) {
      var bytes = new byte[data.readUnsignedByte()];
      data.readFully(bytes);
      var word = new String(bytes, StandardCharsets.UTF_8);
      words.add(word);
      ranks.put(word, rank);
    }
    var vectors = new byte[Math.multiplyExact(count, dimensions)];
    data.readFully(vectors);
    if (data.read() != -1) {
      throw new IOException("bytes after the last vector");
    }
    var inverseNorms = new double[count];
    for (var rank = 0; rank < count; rank++) {
      var sum = 0;
      for (var k = 0; k < dimensions; k++) {
        var x = vectors[rank * dimensions + k];
        sum += x * x;
      }
      inverseNorms[rank] = 1 / Math.sqrt(sum);
    }
    return new WordVectors(words, ranks, dimensions, vectors, inverseNorms);
  }

  public int size() {
    return words.size();
  }

  public int dimensions() {
    return dimensions;
  }

  public String word(int rank) {
    return words.get(rank);
  }

  public OptionalInt rank(String word) {
    var rank = ranks.get(word);
    return rank == null ? OptionalInt.empty() : OptionalInt.of(rank);
  }

  public double similarity(int a, int b) {
    var dot = 0;
    for (int k = 0, i = a * dimensions, j = b * dimensions; k < dimensions; k++) {
      dot += vectors[i + k] * vectors[j + k];
    }
    return dot * inverseNorms[a] * inverseNorms[b];
  }

  public double similarity(String a, String b) {
    return similarity(rankOf(a), rankOf(b));
  }

  /** The {@code k} words closest to the given one, the closest first. */
  public List<Neighbour> nearest(String word, int k) {
    var rank = rankOf(word);
    var closest =
        new PriorityQueue<Neighbour>(k + 1, Comparator.comparingDouble(Neighbour::similarity));
    for (var other = 0; other < size(); other++) {
      if (other != rank) {
        closest.add(new Neighbour(words.get(other), similarity(rank, other)));
        if (closest.size() > k) {
          closest.poll();
        }
      }
    }
    return closest.stream()
        .sorted(Comparator.comparingDouble(Neighbour::similarity).reversed())
        .toList();
  }

  private int rankOf(String word) {
    return rank(word).orElseThrow(() -> new IllegalArgumentException("unknown word: " + word));
  }
}
