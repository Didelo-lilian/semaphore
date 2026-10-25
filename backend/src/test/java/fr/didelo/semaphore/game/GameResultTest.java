package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import fr.didelo.semaphore.game.GameResult.EndReason;
import fr.didelo.semaphore.game.GameResult.Role;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class GameResultTest {

  private static final List<String> POOL =
      IntStream.range(0, 26).mapToObj(i -> (char) ('a' + i) + "voile" + (char) ('a' + i)).toList();

  private final MutableClock clock = new MutableClock();
  // Two players per team: p0 and p1 are BABORD, p2 and p3 TRIBORD.
  private final Room room = new Room("ABCDE", clock, new Random(7), 2, POOL);
  private final List<Player> players =
      IntStream.range(0, 4).mapToObj(i -> room.join("p" + i, "replica")).toList();

  private void start() {
    players.forEach(
        player ->
            room.apply(
                player.getId(),
                new Command.JoinTeam(players.indexOf(player) < 2 ? Team.BABORD : Team.TRIBORD)));
    room.apply(players.get(0).getId(), new Command.Start());
  }

  @Test
  void aTeamThatRevealsAllItsWordsEndsTheGameComplete() {
    start();
    Moves.win(room, Team.TRIBORD);

    var result = GameResult.of(room.state());

    assertThat(result.winner()).isEqualTo(Team.TRIBORD);
    assertThat(result.endReason()).isEqualTo(EndReason.COMPLETE);
    assertThat(result.id()).isEqualTo(room.state().gameId()).isNotNull();
    assertThat(result.endedAt()).isEqualTo(clock.instant());
  }

  @Test
  void theLastWordOfTheOtherTeamIsStillAWholeSetOfWords() {
    start();
    var team = room.state().turn();
    for (var i = 0; i < 8; i++) {
      Moves.signal(room, "voyage", 1);
      Moves.reveal(room, Moves.indexOf(room, team.other().kind(), i));
      if (i < 7) {
        Moves.signal(room, "autre", 1);
        Moves.pass(room);
      }
    }

    var result = GameResult.of(room.state());

    assertThat(result.winner()).isEqualTo(team.other());
    assertThat(result.endReason()).isEqualTo(EndReason.COMPLETE);
  }

  @Test
  void aSecondReefEndsTheGameOnTheReef() {
    start();
    var team = room.state().turn();
    Moves.signal(room, "voyage", 3);
    Moves.reveal(room, Moves.indexOf(room, CardKind.REEF, 0));
    Moves.signal(room, "autre", 1);
    Moves.pass(room);
    Moves.signal(room, "encore", 1);
    Moves.reveal(room, Moves.indexOf(room, CardKind.REEF, 1));

    var result = GameResult.of(room.state());

    assertThat(result.winner()).isEqualTo(team.other());
    assertThat(result.endReason()).isEqualTo(EndReason.REEF);
  }

  @Test
  void aTeamWithoutASignaleurForTooLongForfeits() {
    start();
    var team = room.state().turn();
    room.disconnect(players.get(team.other() == Team.BABORD ? 0 : 2).getId(), "replica");
    clock.advance(Room.SIGNALER_LIMIT);
    room.expire();

    var result = GameResult.of(room.state());

    assertThat(result.winner()).isEqualTo(team);
    assertThat(result.endReason()).isEqualTo(EndReason.FORFEIT);
    assertThat(result.endedAt()).isEqualTo(clock.instant());
  }

  @Test
  void theFirstOfEachTeamIsTheSignaleurAndEveryoneIsKept() {
    start();
    Moves.win(room, Team.BABORD);

    var participants = GameResult.of(room.state()).participants();

    assertThat(participants)
        .extracting(GameResult.Participant::name, GameResult.Participant::role)
        .containsExactly(
            tuple("p0", Role.SIGNALER),
            tuple("p1", Role.LOOKOUT),
            tuple("p2", Role.SIGNALER),
            tuple("p3", Role.LOOKOUT));
  }

  @Test
  void aGameHasAnAccountOnlyWhenAPlayerSignedIn() {
    start();
    Moves.win(room, Team.BABORD);
    assertThat(GameResult.of(room.state()).hasAccount()).isFalse();

    var account = UUID.randomUUID();
    players.get(1).setAccountId(account);

    var result = GameResult.of(room.state());
    assertThat(result.hasAccount()).isTrue();
    assertThat(result.participants())
        .filteredOn(participant -> account.equals(participant.accountId()))
        .extracting(GameResult.Participant::name)
        .containsExactly("p1");
  }
}
