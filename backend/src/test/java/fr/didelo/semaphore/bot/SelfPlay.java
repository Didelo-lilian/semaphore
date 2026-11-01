package fr.didelo.semaphore.bot;

import fr.didelo.semaphore.game.Card;
import fr.didelo.semaphore.game.CardKind;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.Team;
import fr.didelo.semaphore.game.WordList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.IntStream;

/** A whole game between two bots by the rules of {@link Room}, with no room, socket or clock. */
final class SelfPlay {

  private SelfPlay() {}

  static Team play(
      ClueGiver giver, Guesser guesser, Map<Team, Level> levels, Team first, long seed) {
    var random = new SplittableRandom(seed);
    var words = new ArrayList<>(new WordList().words());
    Collections.shuffle(words, random);
    var kinds = new ArrayList<CardKind>();
    kinds.addAll(Collections.nCopies(Room.WORDS_PER_TEAM, CardKind.BABORD));
    kinds.addAll(Collections.nCopies(Room.WORDS_PER_TEAM, CardKind.TRIBORD));
    kinds.addAll(Collections.nCopies(Room.CALM_WATERS, CardKind.CALM));
    kinds.addAll(Collections.nCopies(Room.REEFS, CardKind.REEF));
    Collections.shuffle(kinds, random);
    var board = new ArrayList<Card>();
    for (var i = 0; i < Room.BOARD_SIZE; i++) {
      board.add(new Card(words.get(i), kinds.get(i), false));
    }
    var flags = new EnumMap<Team, Integer>(Team.class);
    for (var team : Team.values()) {
      flags.put(team, Room.FLAGS);
    }

    var turn = first;
    while (true) {
      var level = levels.get(turn);
      var clue = giver.clue(board, turn, level, random);
      var hidden = board.stream().filter(card -> !card.revealed()).map(Card::word).toList();
      for (var word : guesser.picks(hidden, clue, level, random)) {
        var index =
            IntStream.range(0, board.size())
                .filter(i -> board.get(i).word().equals(word))
                .findFirst()
                .orElseThrow();
        var card = board.get(index);
        board.set(index, new Card(card.word(), card.kind(), true));
        for (var team : Team.values()) {
          if (board.stream().filter(c -> c.kind() == team.kind()).allMatch(Card::revealed)) {
            return team;
          }
        }
        if (card.kind() == CardKind.REEF && flags.merge(turn, -1, Integer::sum) == 0) {
          return turn.other();
        }
        if (card.kind() != turn.kind()) {
          break;
        }
      }
      turn = turn.other();
    }
  }
}
