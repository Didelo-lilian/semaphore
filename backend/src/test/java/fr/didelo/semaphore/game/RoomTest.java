package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.game.RoomView.CardView;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RoomTest {

  // Distinct first letters: no word of the pool is too close to another one.
  private static final List<String> POOL =
      IntStream.range(0, 26).mapToObj(i -> (char) ('a' + i) + "voile" + (char) ('a' + i)).toList();

  /** A started game, two players per team. The key is read through the Signaleurs' views. */
  private static class Game {
    final Room room = new Room("ABCDE", Clock.systemUTC(), new Random(7), 2, POOL);
    final List<Player> players =
        List.of(room.join("a1"), room.join("a2"), room.join("b1"), room.join("b2"));

    Game() {
      players.forEach(
          p ->
              room.apply(
                  p.getId(),
                  new Command.JoinTeam(players.indexOf(p) < 2 ? Team.BABORD : Team.TRIBORD)));
      room.apply(players.get(0).getId(), new Command.Start());
    }

    RoomView view() {
      return room.viewFor(players.get(0).getId());
    }

    Team turn() {
      return view().turn();
    }

    Player signalerOf(Team team) {
      var id =
          view().players().stream()
              .filter(v -> v.signaler() && v.team() == team)
              .findFirst()
              .orElseThrow()
              .id();
      return players.stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
    }

    Player signaler() {
      return signalerOf(turn());
    }

    Player lookout() {
      return players.stream()
          .filter(p -> p.getTeam() == turn() && p != signaler())
          .findFirst()
          .orElseThrow();
    }

    List<CardView> key() {
      return room.viewFor(signaler().getId()).board();
    }

    /** The nth card of a kind, whether it is revealed or not. */
    int indexOf(CardKind kind, int nth) {
      return IntStream.range(0, key().size())
          .filter(i -> key().get(i).kind() == kind)
          .skip(nth)
          .findFirst()
          .orElseThrow();
    }

    void signal(String word, int number) {
      room.apply(signaler().getId(), new Command.Signal(word, number));
    }

    void reveal(int index) {
      room.apply(lookout().getId(), new Command.Reveal(index));
    }

    void pass() {
      room.apply(lookout().getId(), new Command.Pass());
    }
  }

  private static void refuses(Runnable move, String reason) {
    assertThatThrownBy(move::run).isInstanceOf(IllegalMove.class).hasMessageContaining(reason);
  }

  @Test
  void lobbyAcceptsPlayersWithValidNamesOnly() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);

    assertThat(room.join("  Ana ").getName()).isEqualTo("Ana");
    refuses(() -> room.join("   "), "name");
    refuses(() -> room.join("x".repeat(21)), "name");
  }

  @Test
  void aRoomHoldsAtMostTwelvePlayers() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);
    IntStream.range(0, Room.MAX_PLAYERS).forEach(i -> room.join("p" + i));

    refuses(() -> room.join("one too many"), "full");
  }

  @Test
  void onlyTheHostStartsAndEveryoneNeedsATeamOfEnoughPlayers() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);
    var host = room.join("host");
    var other = room.join("other");
    var third = room.join("third");

    refuses(() -> room.apply(other.getId(), new Command.Start()), "host");
    refuses(() -> room.apply(host.getId(), new Command.Start()), "pick a team");

    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));
    room.apply(other.getId(), new Command.JoinTeam(Team.BABORD));
    room.apply(third.getId(), new Command.JoinTeam(Team.TRIBORD));
    refuses(() -> room.apply(host.getId(), new Command.Start()), "at least 2");
  }

  @Test
  void nobodyJoinsOnceTheGameHasStarted() {
    refuses(() -> new Game().room.join("late"), "started");
  }

  @Test
  void theGridHasEightWordsPerTeamSixCalmWatersAndTwoReefs() {
    var kinds = new Game().key().stream().map(CardView::kind).toList();

    assertThat(kinds).hasSize(24);
    assertThat(kinds.stream().filter(k -> k == CardKind.BABORD)).hasSize(8);
    assertThat(kinds.stream().filter(k -> k == CardKind.TRIBORD)).hasSize(8);
    assertThat(kinds.stream().filter(k -> k == CardKind.CALM)).hasSize(6);
    assertThat(kinds.stream().filter(k -> k == CardKind.REEF)).hasSize(2);
    assertThat(new Game().key().stream().map(CardView::word).distinct()).hasSize(24);
  }

  @Test
  void onlyTheSignaleursSeeTheKey() {
    var game = new Game();
    var signaleurs = List.of(game.signalerOf(Team.BABORD), game.signalerOf(Team.TRIBORD));

    for (var player : game.players) {
      var board = game.room.viewFor(player.getId()).board();
      if (signaleurs.contains(player)) {
        assertThat(board).allMatch(c -> c.kind() != null);
      } else {
        assertThat(board).allMatch(c -> c.kind() == null);
      }
    }
  }

  @Test
  void aRevealedCardShowsItsKindToEveryone() {
    var game = new Game();
    game.signal("voyage", 2);
    var index = game.indexOf(game.turn().kind(), 0);
    var kind = game.key().get(index).kind();

    game.reveal(index);

    assertThat(game.room.viewFor(game.lookout().getId()).board().get(index).kind()).isEqualTo(kind);
  }

  @Test
  void aCommandIsOnlyLegalInItsPhase() {
    var game = new Game();

    refuses(() -> game.room.apply(game.lookout().getId(), new Command.Reveal(0)), "phase");
    refuses(() -> game.room.apply(game.lookout().getId(), new Command.Pass()), "phase");
    refuses(() -> game.room.apply(game.players.get(0).getId(), new Command.Start()), "phase");
    refuses(
        () -> game.room.apply(game.players.get(0).getId(), new Command.JoinTeam(Team.BABORD)),
        "phase");

    game.signal("voyage", 1);
    refuses(() -> game.signal("autre", 1), "phase");
  }

  @Test
  void onlyTheSignaleurOfTheTeamInPlayCanSignal() {
    var game = new Game();

    game.players.stream()
        .filter(p -> p != game.signaler())
        .forEach(
            p ->
                refuses(
                    () -> game.room.apply(p.getId(), new Command.Signal("voyage", 1)),
                    "Signaleur"));
  }

  @Test
  void aSignalNeedsOneWordAndANumberFromOneToFour() {
    var game = new Game();

    refuses(() -> game.signal("deux mots", 1), "one word");
    refuses(() -> game.signal("a1b2", 1), "one word");
    refuses(() -> game.signal("", 1), "one word");
    refuses(() -> game.signal("voyage", 0), "number");
    refuses(() -> game.signal("voyage", 5), "number");

    game.signal("voyage", 4);
    assertThat(game.view().signal()).isEqualTo(new ActiveSignal("voyage", 4));
    assertThat(game.view().revealsLeft()).isEqualTo(5);
  }

  @Test
  void aSignalTooCloseToAWordOnTheGridIsRefused() {
    var game = new Game();
    var word = game.key().get(0).word();

    Stream.of(word, word.toUpperCase(), word + "s", "x" + word, word.substring(0, 4) + "zzz")
        .forEach(signal -> refuses(() -> game.signal(signal, 1), "too close"));
  }

  @Test
  void aWordAlreadyRevealedIsFreeToSignalAgain() {
    var game = new Game();
    game.signal("voyage", 1);
    var index = game.indexOf(CardKind.CALM, 0);
    var word = game.key().get(index).word();
    game.reveal(index);

    game.signal(word, 1);

    assertThat(game.view().signal().word()).isEqualTo(word);
  }

  @Test
  void accentsDoNotHideACloseSignal() {
    var pool = Stream.concat(Stream.of("éléphant"), POOL.stream().limit(23)).toList();
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(3), 2, pool);
    var players = List.of(room.join("a1"), room.join("a2"), room.join("b1"), room.join("b2"));
    players.forEach(
        p ->
            room.apply(
                p.getId(),
                new Command.JoinTeam(players.indexOf(p) < 2 ? Team.BABORD : Team.TRIBORD)));
    room.apply(players.get(0).getId(), new Command.Start());
    var view = room.viewFor(players.get(0).getId());
    var signalerId =
        view.players().stream()
            .filter(v -> v.signaler() && v.team() == view.turn())
            .findFirst()
            .orElseThrow()
            .id();

    refuses(() -> room.apply(signalerId, new Command.Signal("elephant", 1)), "too close");
  }

  @Test
  void onlyTheLookoutsOfTheTeamInPlayRevealAndOnlyHiddenCards() {
    var game = new Game();
    game.signal("voyage", 3);

    game.players.stream()
        .filter(p -> p != game.lookout())
        .forEach(p -> refuses(() -> game.room.apply(p.getId(), new Command.Reveal(0)), "lookouts"));
    refuses(() -> game.reveal(-1), "hidden");
    refuses(() -> game.reveal(24), "hidden");
    var index = game.indexOf(game.turn().kind(), 0);
    game.reveal(index);
    refuses(() -> game.reveal(index), "hidden");
  }

  @Test
  void revealingOwnWordsKeepsTheTurnUntilTheLimitThenItPasses() {
    var game = new Game();
    var team = game.turn();
    game.signal("voyage", 1);

    game.reveal(game.indexOf(team.kind(), 0));
    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().phase()).isEqualTo(Phase.GUESS);
    assertThat(game.view().revealsLeft()).isEqualTo(1);

    game.reveal(game.indexOf(team.kind(), 1));
    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);
    assertThat(game.view().signal()).isNull();
  }

  @Test
  void calmWatersAndTheOtherTeamsWordEndTheTurn() {
    var game = new Game();
    var team = game.turn();
    game.signal("voyage", 3);
    game.reveal(game.indexOf(CardKind.CALM, 0));
    assertThat(game.turn()).isEqualTo(team.other());

    game.signal("autre", 3);
    var theirs = game.indexOf(team.kind(), 0);
    game.reveal(theirs);

    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().board().get(theirs).revealed()).isTrue();
  }

  @Test
  void aReefCostsAFlagAndEndsTheTurnAndTheSecondOneLosesTheGame() {
    var game = new Game();
    var team = game.turn();
    game.signal("voyage", 3);

    game.reveal(game.indexOf(CardKind.REEF, 0));
    assertThat(game.view().flags().get(team)).isEqualTo(1);
    assertThat(game.turn()).isEqualTo(team.other());

    game.signal("autre", 1);
    game.pass();
    game.signal("encore", 1);
    game.reveal(game.indexOf(CardKind.REEF, 1));

    assertThat(game.view().phase()).isEqualTo(Phase.ENDED);
    assertThat(game.view().winner()).isEqualTo(team.other());
  }

  @Test
  void passingEndsTheTurn() {
    var game = new Game();
    var team = game.turn();
    game.signal("voyage", 2);

    game.pass();

    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);
  }

  @Test
  void aTeamWinsWhenAllItsWordsAreRevealed() {
    var game = new Game();
    var team = game.turn();
    game.signal("voyage", 4);
    IntStream.range(0, 5).forEach(i -> game.reveal(game.indexOf(team.kind(), i)));
    assertThat(game.turn()).isEqualTo(team.other());
    game.signal("autre", 1);
    game.pass();

    game.signal("suite", 3);
    IntStream.range(5, 8).forEach(i -> game.reveal(game.indexOf(team.kind(), i)));

    assertThat(game.view().phase()).isEqualTo(Phase.ENDED);
    assertThat(game.view().winner()).isEqualTo(team);
  }

  @Test
  void revealingTheLastWordOfTheOtherTeamGivesThemTheWin() {
    var game = new Game();
    var team = game.turn();
    for (var i = 0; i < 8; i++) {
      game.signal("voyage", 1);
      game.reveal(game.indexOf(team.other().kind(), i));
      if (i < 7) {
        game.signal("autre", 1);
        game.pass();
      }
    }

    assertThat(game.view().phase()).isEqualTo(Phase.ENDED);
    assertThat(game.view().winner()).isEqualTo(team.other());
  }

  @Test
  void theSignaleurRotatesEachTurnTheTeamPlays() {
    var game = new Game();
    var team = game.turn();
    var first = game.signaler();
    game.signal("voyage", 1);
    game.pass();
    game.signal("autre", 1);
    game.pass();

    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.signaler()).isNotSameAs(first);
  }

  @Test
  void aFinishedGameRefusesEverythingAndShowsTheKey() {
    var game = new Game();
    game.signal("voyage", 3);
    game.reveal(game.indexOf(CardKind.REEF, 0));
    game.signal("autre", 1);
    game.pass();
    game.signal("encore", 1);
    game.reveal(game.indexOf(CardKind.REEF, 1));

    refuses(() -> game.room.apply(game.players.get(0).getId(), new Command.Pass()), "phase");
    refuses(
        () -> game.room.apply(game.players.get(0).getId(), new Command.Signal("fin", 1)), "phase");
    assertThat(game.players)
        .allMatch(
            p -> game.room.viewFor(p.getId()).board().stream().allMatch(c -> c.kind() != null));
  }

  @Test
  void aRoomNobodyIsConnectedToIsAbandonedAfterTheDelay() {
    var clock = new MutableClock();
    var room = new Room("ABCDE", clock, new Random(1), 2, POOL);
    var player = room.join("ana");

    room.setConnected(player.getId(), false);
    assertThat(room.abandonedFor(Duration.ofMinutes(30))).isFalse();
    clock.advance(Duration.ofMinutes(31));
    assertThat(room.abandonedFor(Duration.ofMinutes(30))).isTrue();

    room.setConnected(player.getId(), true);
    clock.advance(Duration.ofMinutes(31));
    assertThat(room.abandonedFor(Duration.ofMinutes(30))).isFalse();
  }
}
