package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import fr.didelo.semaphore.game.RoomView.PlayerView;
import java.time.Duration;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Bots as players of a room (ADR 0016): the seats, the roles, the presence and who has to act. */
class RoomBotsTest {

  private final MutableClock clock = new MutableClock();
  private final Room room = new Room("ABCDE", clock, new Random(7), 2, Moves.POOL);
  private final Player host = room.join("Alice", null, Moves.REPLICA);

  private void addBot(Team team, boolean signaler) {
    room.apply(host.getId(), new Command.AddBot(team, signaler, "NORMAL"));
  }

  private PlayerView seat(String name) {
    return room.viewFor(host.getId()).players().stream()
        .filter(p -> p.name().equals(name))
        .findFirst()
        .orElseThrow();
  }

  /** Alice and Bot 1 as its lookout on Bâbord, Bots 2 and 3 on Tribord, the game started. */
  private void startWithBots() {
    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));
    addBot(Team.BABORD, false);
    addBot(Team.TRIBORD, true);
    addBot(Team.TRIBORD, false);
    room.apply(host.getId(), new Command.Start());
  }

  @Test
  void aGameKeepsItsBotsAsParticipantsWithoutAccountAndOnlyForSomeoneSignedIn() {
    var account = UUID.randomUUID();
    var signedIn = new Room("ABCDE", clock, new Random(7), 2, Moves.POOL);
    var alice = signedIn.join("Alice", account, Moves.REPLICA);
    signedIn.apply(alice.getId(), new Command.JoinTeam(Team.BABORD));
    signedIn.apply(alice.getId(), new Command.AddBot(Team.BABORD, false, "EASY"));
    signedIn.apply(alice.getId(), new Command.AddBot(Team.TRIBORD, true, "NORMAL"));
    signedIn.apply(alice.getId(), new Command.AddBot(Team.TRIBORD, false, "HARD"));
    signedIn.apply(alice.getId(), new Command.Start());
    Moves.win(signedIn, Team.BABORD);

    var result = signedIn.result().orElseThrow();

    assertThat(result.hasAccount()).isTrue();
    assertThat(result.participants())
        .extracting(GameResult.Participant::name, GameResult.Participant::accountId)
        .containsExactlyInAnyOrder(
            tuple("Alice", account),
            tuple("Bot 1", null),
            tuple("Bot 2", null),
            tuple("Bot 3", null));

    startWithBots();
    Moves.win(room, Team.BABORD);
    assertThat(room.result().orElseThrow().hasAccount()).isFalse();
  }

  @Test
  void aBotTakesASeatAsAPlayerWhoIsNeverAway() {
    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));

    addBot(Team.BABORD, false);

    var bot = seat("Bot 1");
    assertThat(bot.bot()).isEqualTo("NORMAL");
    assertThat(bot.team()).isEqualTo(Team.BABORD);
    assertThat(bot.connected()).isTrue();
    assertThat(bot.host()).isFalse();
    assertThat(seat("Alice").host()).isTrue();
    assertThat(seat("Alice").bot()).isNull();
  }

  @Test
  void theSignaleurIsTheFirstOfTheTeamAndABotCanTakeThePlace() {
    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));
    assertThat(seat("Alice").signaler()).isTrue();

    addBot(Team.BABORD, true);

    assertThat(seat("Bot 1").signaler()).isTrue();
    assertThat(seat("Alice").signaler()).isFalse();
    assertThat(seat("Alice").host()).isTrue();
  }

  @Test
  void aLookoutBotNeedsASignaleurFirstAndTheTeamHasOneSignaleur() {
    assertThatThrownBy(() -> addBot(Team.TRIBORD, false)).isInstanceOf(IllegalMove.class);

    addBot(Team.TRIBORD, true);

    assertThatThrownBy(() -> addBot(Team.TRIBORD, true)).isInstanceOf(IllegalMove.class);
    addBot(Team.TRIBORD, false);
    assertThat(seat("Bot 2").signaler()).isFalse();
  }

  @Test
  void onlyTheHostAddsAndRemovesBotsAndOnlyBotsAreRemoved() {
    var guest = room.join("Bob", null, Moves.REPLICA);
    addBot(Team.BABORD, true);
    var bot =
        room.state().players().stream().filter(p -> p.bot() != null).findFirst().orElseThrow();

    assertThatThrownBy(
            () -> room.apply(guest.getId(), new Command.AddBot(Team.BABORD, false, "EASY")))
        .isInstanceOf(IllegalMove.class);
    assertThatThrownBy(() -> room.apply(guest.getId(), new Command.RemoveBot(bot.id())))
        .isInstanceOf(IllegalMove.class);
    assertThatThrownBy(() -> room.apply(host.getId(), new Command.RemoveBot(guest.getId())))
        .isInstanceOf(IllegalMove.class);

    room.apply(host.getId(), new Command.RemoveBot(bot.id()));

    assertThat(room.state().players())
        .extracting(RoomState.PlayerState::name)
        .containsExactly("Alice", "Bob");
  }

  @Test
  void aBotTakesASeatLikeAnyPlayerAndTheNamesStayDistinct() {
    addBot(Team.BABORD, true);
    addBot(Team.BABORD, false);
    var first =
        room.state().players().stream()
            .filter(p -> p.name().equals("Bot 1"))
            .findFirst()
            .orElseThrow();
    room.apply(host.getId(), new Command.RemoveBot(first.id()));

    addBot(Team.TRIBORD, true);

    assertThat(room.state().players())
        .extracting(RoomState.PlayerState::name)
        .contains("Bot 1", "Bot 2");
    assertThat(room.state().players())
        .extracting(RoomState.PlayerState::name)
        .doesNotHaveDuplicates();
    while (room.state().players().size() < Room.MAX_PLAYERS) {
      room.join("p" + room.state().players().size(), null, Moves.REPLICA);
    }
    assertThatThrownBy(() -> addBot(Team.BABORD, false)).hasMessageContaining("full");
  }

  @Test
  void aBotIsNotAddedOnceTheGameHasStarted() {
    startWithBots();

    assertThatThrownBy(() -> addBot(Team.BABORD, false)).isInstanceOf(IllegalMove.class);
  }

  @Test
  void theGameStartsWithBotsAndTheyKeepTheirSeats() {
    startWithBots();

    assertThat(room.phase()).isEqualTo(Phase.SIGNAL);
    assertThat(room.state().players()).filteredOn(p -> p.bot() != null).hasSize(3);
    assertThat(room.result()).isEmpty();
  }

  @Test
  void theBotThatHasToActIsTheSignaleurOrTheLookoutsWhenTheyAreAllBots() {
    startWithBots();
    var inPlay = room.state().turn();

    // Alice signals on Bâbord, where Bot 1 is the only lookout; Tribord is two bots.
    if (inPlay == Team.BABORD) {
      assertThat(room.botToAct()).isEmpty();
      room.apply(host.getId(), new Command.Signal("zzyzx", 1));
      assertThat(room.botToAct()).map(Player::getName).contains("Bot 1");
    } else {
      assertThat(room.botToAct()).map(Player::getName).contains("Bot 2");
      room.apply(room.botToAct().orElseThrow().getId(), new Command.Signal("zzyzx", 1));
      assertThat(room.botToAct()).map(Player::getName).contains("Bot 3");
    }
  }

  @Test
  void aHumanLookoutPlaysAndABotBesideThemDoesNotTalkOverThem() {
    var bob = room.join("Bob", null, Moves.REPLICA);
    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));
    room.apply(bob.getId(), new Command.JoinTeam(Team.BABORD));
    addBot(Team.BABORD, false);
    addBot(Team.TRIBORD, true);
    addBot(Team.TRIBORD, false);
    room.apply(host.getId(), new Command.Start());
    if (room.state().turn() == Team.TRIBORD) {
      var tribord = room.botToAct().orElseThrow();
      room.apply(tribord.getId(), new Command.Signal("zzyzx", 1));
      room.apply(room.botToAct().orElseThrow().getId(), new Command.Pass());
    }

    room.apply(host.getId(), new Command.Signal("zzyzx", 1));

    assertThat(room.botToAct()).isEmpty();
  }

  @Test
  void aBotWaitsForNoHumanToBeOnlineAndNoOneWaitsForTheBot() {
    startWithBots();
    if (room.state().turn() == Team.BABORD) {
      room.apply(host.getId(), new Command.Signal("zzyzx", 1));
      room.apply(room.botToAct().orElseThrow().getId(), new Command.Pass());
    }
    assertThat(room.botToAct()).isPresent();

    room.disconnect(host.getId(), Moves.REPLICA);
    clock.advance(Room.TURN_GRACE.plusSeconds(1));

    assertThat(room.botToAct()).isEmpty();
    assertThat(room.viewFor(room.state().players().get(1).id()).turnPassesIn()).isNull();
  }

  @Test
  void aBotIsNeverMarkedAbsentByAReplicaThatStoppedBeating() {
    startWithBots();

    room.dropReplicasNotIn(Set.of("replica-2"));

    assertThat(room.state().players())
        .filteredOn(p -> p.bot() != null)
        .allSatisfy(p -> assertThat(p.absentSince()).isNull());
    assertThat(seat("Alice").connected()).isFalse();
  }

  @Test
  void aRoomOfBotsAndAnAbsentHumanIsAbandonedAsAnyRoom() {
    startWithBots();
    room.disconnect(host.getId(), Moves.REPLICA);

    assertThat(room.abandonedFor(Duration.ofMinutes(30))).isFalse();
    clock.advance(Duration.ofMinutes(31));

    assertThat(room.abandonedFor(Duration.ofMinutes(30))).isTrue();
  }

  @Test
  void theBotsLeaveTheLobbyWithTheLastHuman() {
    addBot(Team.BABORD, true);
    addBot(Team.TRIBORD, true);

    room.leave(host.getId());

    assertThat(room.state().players()).isEmpty();
  }

  @Test
  void theBotsOfARoomComeBackWithTheirLevelAndOrder() {
    room.apply(host.getId(), new Command.JoinTeam(Team.BABORD));
    addBot(Team.BABORD, true);
    room.apply(host.getId(), new Command.AddBot(Team.TRIBORD, true, "HARD"));

    var restored = Room.restore(room.state(), clock, new Random(7), 2, Moves.POOL);

    assertThat(restored.state()).isEqualTo(room.state());
    assertThat(restored.viewFor(host.getId()).players())
        .extracting(PlayerView::name, PlayerView::bot)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Bot 2", "HARD"),
            org.assertj.core.groups.Tuple.tuple("Bot 1", "NORMAL"),
            org.assertj.core.groups.Tuple.tuple("Alice", null));
    assertThat(Optional.of(restored.phase())).contains(Phase.LOBBY);
  }
}
