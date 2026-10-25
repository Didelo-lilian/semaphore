package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.didelo.semaphore.game.RoomView.CardView;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class RoomTest {

  // Distinct first letters: no word of the pool is too close to another one.
  private static final List<String> POOL =
      IntStream.range(0, 26).mapToObj(i -> (char) ('a' + i) + "voile" + (char) ('a' + i)).toList();

  /**
   * A started game, two players per team by default. The key is read through the Signaleurs' views.
   */
  private static class Game {
    final Room room;
    final List<Player> players;

    Game() {
      this(POOL, Clock.systemUTC());
    }

    Game(List<String> pool, Clock clock) {
      this(pool, clock, 2);
    }

    Game(List<String> pool, Clock clock, int teamSize) {
      room = new Room("ABCDE", clock, new Random(7), 2, pool);
      players = IntStream.range(0, 2 * teamSize).mapToObj(i -> room.join("p" + i)).toList();
      players.forEach(
          p ->
              room.apply(
                  p.getId(),
                  new Command.JoinTeam(
                      players.indexOf(p) < teamSize ? Team.BABORD : Team.TRIBORD)));
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

    List<Player> lookouts() {
      return players.stream().filter(p -> p.getTeam() == turn() && p != signaler()).toList();
    }

    Player lookout() {
      return lookouts().get(0);
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
  void accentsAndLigaturesDoNotHideACloseSignal() {
    var pool = Stream.concat(Stream.of("éléphant", "cœur"), POOL.stream().limit(22)).toList();
    var game = new Game(pool, Clock.systemUTC());

    refuses(() -> game.signal("elephant", 1), "too close");
    refuses(() -> game.signal("coeur", 1), "too close");
    game.signal("sœur", 1);
    assertThat(game.view().signal().word()).isEqualTo("sœur");
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
  void theSignaleurIsTheFirstOfTheTeamAndStaysForTheWholeGrid() {
    var game = new Game();
    var babord = game.signalerOf(Team.BABORD);
    var tribord = game.signalerOf(Team.TRIBORD);

    for (var i = 0; i < 4; i++) {
      game.signal("voyage", 1);
      game.pass();
    }

    assertThat(babord).isSameAs(game.players.get(0));
    assertThat(tribord).isSameAs(game.players.get(2));
    assertThat(game.signalerOf(Team.BABORD)).isSameAs(babord);
    assertThat(game.signalerOf(Team.TRIBORD)).isSameAs(tribord);
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
    var game = new Game(POOL, clock);

    game.players.forEach(p -> game.room.disconnect(p.getId()));
    assertThat(game.room.abandonedFor(Duration.ofMinutes(30))).isFalse();
    clock.advance(Duration.ofMinutes(31));
    assertThat(game.room.abandonedFor(Duration.ofMinutes(30))).isTrue();

    game.room.reconnect(game.players.get(1).getId());
    clock.advance(Duration.ofMinutes(31));
    assertThat(game.room.abandonedFor(Duration.ofMinutes(30))).isFalse();
  }

  @Test
  void aSpectatorSeesWhatALookoutSeesFromNoSeat() {
    var game = new Game();
    game.room.watch();
    var lookout = game.room.viewFor(game.lookout().getId());

    assertThat(game.room.spectatorView())
        .usingRecursiveComparison()
        .ignoringFields("you")
        .isEqualTo(lookout);
    assertThat(game.room.spectatorView().you()).isNull();
    assertThat(game.room.spectatorView().spectators()).isEqualTo(1);

    game.signal("voyage", 2);
    game.reveal(game.indexOf(game.turn().kind(), 0));
    assertThat(game.room.spectatorView())
        .usingRecursiveComparison()
        .ignoringFields("you")
        .isEqualTo(game.room.viewFor(game.lookout().getId()));
  }

  @Test
  void theKeyNeverReachesASpectatorBeforeTheEnd() {
    var game = new Game();
    game.room.watch();
    var team = game.turn();
    var views = new ArrayList<RoomView>();
    views.add(game.room.spectatorView());

    game.signal("voyage", 3);
    views.add(game.room.spectatorView());
    game.reveal(game.indexOf(team.kind(), 0));
    views.add(game.room.spectatorView());
    game.reveal(game.indexOf(CardKind.REEF, 0));
    views.add(game.room.spectatorView());
    game.signal("autre", 1);
    views.add(game.room.spectatorView());
    game.pass();
    views.add(game.room.spectatorView());
    game.signal("encore", 1);
    views.add(game.room.spectatorView());
    game.reveal(game.indexOf(CardKind.REEF, 1));
    views.add(game.room.spectatorView());

    var ended = views.remove(views.size() - 1);
    assertThat(views)
        .allSatisfy(
            view -> {
              assertThat(view.phase()).isNotEqualTo(Phase.ENDED);
              assertThat(view.board())
                  .allSatisfy(card -> assertThat(card.kind() != null).isEqualTo(card.revealed()));
            });
    assertThat(ended.phase()).isEqualTo(Phase.ENDED);
    assertThat(ended.board()).allMatch(card -> card.kind() != null);
  }

  @Test
  void aLobbyHasNothingToWatch() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);
    room.join("host");

    refuses(room::watch, "not started");
  }

  @Test
  void aRoomHoldsTwentySpectatorsOnTopOfItsPlayersAndEveryoneSeesTheCount() {
    var game = new Game();
    IntStream.range(0, Room.MAX_SPECTATORS).forEach(i -> game.room.watch());

    refuses(game.room::watch, "too many");
    assertThat(game.view().spectators()).isEqualTo(Room.MAX_SPECTATORS);

    game.room.unwatch();
    game.room.watch();
    game.room.unwatch();
    assertThat(game.view().spectators()).isEqualTo(Room.MAX_SPECTATORS - 1);
  }

  @Test
  void watchingAndLeavingChangeTheVersionSoViewsAreResent() {
    var game = new Game();
    var before = game.view().version();

    game.room.watch();
    var watching = game.view().version();
    game.room.unwatch();

    assertThat(watching).isGreaterThan(before);
    assertThat(game.view().version()).isGreaterThan(watching);
  }

  @Test
  void spectatorsDoNotKeepARoomAlive() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    game.players.forEach(p -> game.room.disconnect(p.getId()));

    clock.advance(Duration.ofMinutes(29));
    game.room.watch();
    clock.advance(Duration.ofMinutes(2));
    assertThat(game.room.abandonedFor(Duration.ofMinutes(30))).isTrue();

    game.room.unwatch();
    assertThat(game.room.abandonedFor(Duration.ofMinutes(30))).isTrue();
  }

  @Test
  void aPlayerWhoLeavesTheLobbyIsGoneAtOnceAndTheHostSeatMovesOn() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);
    var host = room.join("host");
    var other = room.join("other");

    room.leave(host.getId());

    var view = room.viewFor(other.getId());
    assertThat(view.players()).hasSize(1);
    assertThat(view.players().get(0).host()).isTrue();
    assertThat(room.byToken(host.getToken())).isEmpty();
  }

  @Test
  void aPlayerWhoDropsFromTheLobbyIsKeptOfflineForTheGracePeriodAndComesBackWithTheirTeam() {
    var clock = new MutableClock();
    var room = new Room("ABCDE", clock, new Random(1), 2, POOL);
    var host = room.join("host");
    var other = room.join("other");
    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));

    room.disconnect(host.getId());
    clock.advance(Room.LOBBY_GRACE);

    assertThat(room.dropAbsent()).isFalse();
    var view = room.viewFor(other.getId());
    assertThat(view.players()).hasSize(2);
    assertThat(view.players().get(0).connected()).isFalse();
    assertThat(view.players().get(0).host()).isTrue();

    room.reconnect(host.getId());
    clock.advance(Room.LOBBY_GRACE.plusSeconds(1));

    assertThat(room.dropAbsent()).isFalse();
    assertThat(room.viewFor(other.getId()).players().get(0).team()).isEqualTo(Team.BABORD);
  }

  @Test
  void aLobbyDropsThePlayersAbsentForLongerThanTheGracePeriodAndTheHostSeatMovesOn() {
    var clock = new MutableClock();
    var room = new Room("ABCDE", clock, new Random(1), 2, POOL);
    var host = room.join("host");
    var other = room.join("other");
    room.disconnect(host.getId());
    var before = room.viewFor(other.getId()).version();

    clock.advance(Room.LOBBY_GRACE.plusSeconds(1));

    assertThat(room.dropAbsent()).isTrue();
    var view = room.viewFor(other.getId());
    assertThat(view.version()).isGreaterThan(before);
    assertThat(view.players()).hasSize(1);
    assertThat(view.players().get(0).host()).isTrue();
    assertThat(room.byToken(host.getToken())).isEmpty();
    assertThat(room.dropAbsent()).isFalse();
  }

  @Test
  void aPlayerWhoLeavesTheLobbyWithoutATeamDoesNotBlockTheStart() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);
    var players = List.of(room.join("a1"), room.join("a2"), room.join("b1"), room.join("b2"));
    players.forEach(
        p ->
            room.apply(
                p.getId(),
                new Command.JoinTeam(players.indexOf(p) < 2 ? Team.BABORD : Team.TRIBORD)));
    var ghost = room.join("ghost");
    refuses(() -> room.apply(players.get(0).getId(), new Command.Start()), "pick a team");

    room.leave(ghost.getId());
    room.apply(players.get(0).getId(), new Command.Start());

    assertThat(room.phase()).isEqualTo(Phase.SIGNAL);
  }

  @Test
  void theGameDoesNotStartWhileSomeoneIsOffline() {
    var room = new Room("ABCDE", Clock.systemUTC(), new Random(1), 2, POOL);
    var players = List.of(room.join("a1"), room.join("a2"), room.join("b1"), room.join("b2"));
    players.forEach(
        p ->
            room.apply(
                p.getId(),
                new Command.JoinTeam(players.indexOf(p) < 2 ? Team.BABORD : Team.TRIBORD)));

    room.disconnect(players.get(3).getId());
    refuses(() -> room.apply(players.get(0).getId(), new Command.Start()), "online");

    room.reconnect(players.get(3).getId());
    room.apply(players.get(0).getId(), new Command.Start());

    assertThat(room.phase()).isEqualTo(Phase.SIGNAL);
  }

  @Test
  void aPlayerWhoDropsDuringTheGameKeepsTheSeatAndComesBack() {
    var game = new Game();
    var player = game.players.get(1);

    game.room.disconnect(player.getId());
    assertThat(game.view().players().get(1).connected()).isFalse();
    game.room.reconnect(player.getId());

    assertThat(game.view().players().get(1).connected()).isTrue();
    assertThat(game.room.byToken(player.getToken())).contains(player);
  }

  @Test
  void aPlayerWhoLeavesAGameStaysOfflineLikeAfterAClosedConnection() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var player = game.players.get(1);

    game.room.leave(player.getId());
    clock.advance(Room.LOBBY_GRACE.multipliedBy(10));

    assertThat(game.room.dropAbsent()).isFalse();
    assertThat(game.view().players().get(1).connected()).isFalse();
    assertThat(game.room.byToken(player.getToken())).contains(player);
  }

  @Test
  void aSignaleurAwayForTheWholeDelayLosesTheTurnAndTheDelayStartsAgainOnTheirNextTurn() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    game.room.disconnect(game.signaler().getId());

    clock.advance(Room.TURN_GRACE.minusSeconds(1));
    assertThat(game.room.expire()).isFalse();
    assertThat(game.turn()).isEqualTo(team);
    clock.advance(Duration.ofSeconds(1));
    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);

    game.signal("voyage", 1);
    game.pass();
    assertThat(game.turn()).isEqualTo(team);
    clock.advance(Room.TURN_GRACE.minusSeconds(1));
    assertThat(game.room.expire()).isFalse();
    clock.advance(Duration.ofSeconds(1));
    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team.other());
  }

  @Test
  void aSignaleurWhoComesBackInTimeKeepsTheTurn() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    var signaler = game.signaler();
    game.room.disconnect(signaler.getId());

    clock.advance(Room.TURN_GRACE.minusSeconds(1));
    game.room.reconnect(signaler.getId());
    clock.advance(Room.TURN_GRACE.multipliedBy(2));

    assertThat(game.room.expire()).isFalse();
    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().turnPassesIn()).isNull();
  }

  @Test
  void duringGuessTheTurnPassesOnceEveryLookoutOfTheTeamInPlayIsAwayForTheWholeDelay() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock, 3);
    game.signal("voyage", 2);
    var team = game.turn();
    var lookouts = game.lookouts();
    // Nobody waits for the Signaleur once the signal is given.
    game.room.disconnect(game.signaler().getId());
    game.room.disconnect(lookouts.get(0).getId());

    clock.advance(Room.TURN_GRACE);
    assertThat(game.room.expire()).isFalse();
    game.room.disconnect(lookouts.get(1).getId());
    clock.advance(Room.TURN_GRACE.minusSeconds(1));
    assertThat(game.room.expire()).isFalse();
    clock.advance(Duration.ofSeconds(1));

    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);
    assertThat(game.view().signal()).isNull();
  }

  @Test
  void theViewTellsTheSecondsLeftBeforeTheTurnPasses() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    assertThat(game.view().turnPassesIn()).isNull();

    game.room.disconnect(game.signaler().getId());
    assertThat(game.view().turnPassesIn()).isEqualTo(60);
    clock.advance(Duration.ofMillis(1500));
    assertThat(game.view().turnPassesIn()).isEqualTo(59);
    clock.advance(Room.TURN_GRACE);
    assertThat(game.view().turnPassesIn()).isZero();
  }

  @Test
  void aSignaleurWhoLeavesPassesTheTurnAtOnceAndTheirTeamKeepsNoTurn() {
    var game = new Game(POOL, new MutableClock());
    var team = game.turn();

    game.room.leave(game.signaler().getId());

    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);
    assertThat(game.view().turnPassesIn()).isNull();
    game.signal("voyage", 1);
    game.pass();
    assertThat(game.turn()).isEqualTo(team.other());
  }

  @Test
  void duringGuessTheTurnPassesAtOnceWhenEveryLookoutLeft() {
    var game = new Game(POOL, new MutableClock(), 3);
    game.signal("voyage", 2);
    var team = game.turn();
    var lookouts = game.lookouts();

    game.room.leave(lookouts.get(0).getId());
    assertThat(game.turn()).isEqualTo(team);
    game.room.leave(lookouts.get(1).getId());

    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);
  }

  @Test
  void aLookoutWhoLeftDoesNotShortenTheWaitForAnotherOneWhoIsJustAway() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock, 3);
    game.signal("voyage", 2);
    var team = game.turn();
    var lookouts = game.lookouts();

    game.room.leave(lookouts.get(0).getId());
    game.room.disconnect(lookouts.get(1).getId());

    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().turnPassesIn()).isEqualTo(60);
    clock.advance(Room.TURN_GRACE);
    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team.other());
  }

  @Test
  void aPlayerWhoLeftAndComesBackIsWaitedForAgainLikeAnyoneAway() {
    var game = new Game(POOL, new MutableClock());
    var team = game.turn();
    var signaler = game.signaler();
    game.room.leave(signaler.getId());
    game.room.reconnect(signaler.getId());
    game.room.disconnect(signaler.getId());

    game.signal("voyage", 1);
    game.pass();

    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().turnPassesIn()).isEqualTo(60);
  }

  @Test
  void aRefusedCommandDoesNotRestartTheWait() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    game.room.disconnect(game.signaler().getId());
    clock.advance(Room.TURN_GRACE.minusSeconds(1));

    refuses(
        () -> game.room.apply(game.lookout().getId(), new Command.Signal("voyage", 1)),
        "Signaleur");
    clock.advance(Duration.ofSeconds(1));

    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team.other());
  }

  @Test
  void aTurnThatPassesToATeamWhoseSignaleurLeftPassesAgainInTheSameSweep() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    game.room.disconnect(game.signaler().getId());
    game.room.leave(game.signalerOf(team.other()).getId());
    assertThat(game.turn()).isEqualTo(team);

    clock.advance(Room.TURN_GRACE);

    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().turnPassesIn()).isEqualTo(60);
  }

  @Test
  void whenBothSignaleursLeftTheTurnWaitsTheDelayInsteadOfBouncing() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    game.room.leave(game.signalerOf(team.other()).getId());
    assertThat(game.turn()).isEqualTo(team);

    game.room.leave(game.signaler().getId());

    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.view().turnPassesIn()).isEqualTo(60);
    clock.advance(Room.TURN_GRACE);
    assertThat(game.room.expire()).isTrue();
    assertThat(game.turn()).isEqualTo(team.other());
    assertThat(game.room.expire()).isFalse();
  }

  @Test
  void aTeamWhoseSignaleurHasBeenAwayForTheLimitLosesWhoeverIsInPlay() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    game.room.disconnect(game.signalerOf(team.other()).getId());

    clock.advance(Room.SIGNALER_LIMIT.minusSeconds(1));
    assertThat(game.room.expire()).isFalse();
    assertThat(game.view().phase()).isEqualTo(Phase.SIGNAL);
    clock.advance(Duration.ofSeconds(1));

    assertThat(game.room.expire()).isTrue();
    assertThat(game.view().phase()).isEqualTo(Phase.ENDED);
    assertThat(game.view().winner()).isEqualTo(team);
    assertThat(game.view().turnPassesIn()).isNull();
    refuses(() -> game.signal("voyage", 1), "phase");
    assertThat(game.room.expire()).isFalse();
  }

  @Test
  void whenBothSignaleursAreAwayTheOneAwayLongestLoses() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    var team = game.turn();
    game.room.disconnect(game.signalerOf(team.other()).getId());
    clock.advance(Duration.ofMinutes(1));
    game.room.disconnect(game.signaler().getId());
    clock.advance(Room.SIGNALER_LIMIT);

    assertThat(game.room.expire()).isTrue();

    assertThat(game.view().winner()).isEqualTo(team);
  }

  @Test
  void aMissingSignaleurIsNeverReplacedByALookout() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock, 3);
    var team = game.turn();
    var signaler = game.signaler();
    var lookouts = game.lookouts();
    game.room.disconnect(signaler.getId());
    clock.advance(Room.TURN_GRACE);
    game.room.expire();
    game.signal("voyage", 1);
    game.pass();

    assertThat(game.turn()).isEqualTo(team);
    assertThat(game.signalerOf(team)).isSameAs(signaler);
    refuses(
        () -> game.room.apply(lookouts.get(0).getId(), new Command.Signal("autre", 1)),
        "Signaleur");
    assertThat(game.room.viewFor(lookouts.get(0).getId()).board()).allMatch(c -> c.kind() == null);
  }

  @Test
  void theVersionGrowsWithEveryChangeAndIsTheSameForEveryone() {
    var game = new Game();
    var before = game.view().version();

    game.signal("voyage", 1);

    assertThat(game.view().version()).isGreaterThan(before);
    assertThat(game.players)
        .allMatch(p -> game.room.viewFor(p.getId()).version() == game.view().version());
  }

  @Test
  void aRoomRestoredFromItsStatePlaysAndLooksLikeTheOriginal() {
    var clock = new MutableClock();
    var game = new Game(POOL, clock);
    game.room.disconnect(game.signalerOf(game.turn().other()).getId());
    game.signal("voyage", 2);
    game.reveal(game.indexOf(game.turn().kind(), 0));
    game.room.watch();

    var copy = Room.restore(game.room.state(), clock, new Random(7), 2, POOL);

    assertThat(copy.state()).isEqualTo(game.room.state());
    assertThat(game.players)
        .allSatisfy(
            p -> assertThat(copy.viewFor(p.getId())).isEqualTo(game.room.viewFor(p.getId())));
    assertThat(copy.spectatorView()).isEqualTo(game.room.spectatorView());
    copy.apply(game.lookout().getId(), new Command.Pass());
    assertThat(copy.version()).isGreaterThan(game.room.version());
    assertThat(game.view().turn()).isNotEqualTo(copy.viewFor(game.players.get(0).getId()).turn());
  }
}
