package fr.didelo.semaphore.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import fr.didelo.semaphore.game.GameResult.Role;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameResultTest {

  private final MutableClock clock = new MutableClock();
  // Two players per team: p0 and p1 are BABORD, p2 and p3 TRIBORD.
  private final Room room = Moves.startedRoom(clock, null);

  @Test
  void aGameThatIsNotOverHasNoResult() {
    assertThat(room.result()).isEmpty();
  }

  @Test
  void aTeamThatRevealsAllItsWordsEndsTheGameComplete() {
    Moves.win(room, Team.TRIBORD);

    var result = room.result().orElseThrow();

    assertThat(result.winner()).isEqualTo(Team.TRIBORD);
    assertThat(result.endReason()).isEqualTo(EndReason.COMPLETE);
    assertThat(result.id()).isEqualTo(room.state().gameId()).isNotNull();
    assertThat(result.endedAt()).isEqualTo(clock.instant());
  }

  @Test
  void theLastWordOfTheOtherTeamIsStillAWholeSetOfWords() {
    var team = room.state().turn();
    for (var i = 0; i < 8; i++) {
      Moves.signal(room, "voyage", 1);
      Moves.reveal(room, Moves.indexOf(room, team.other().kind(), i));
      if (i < 7) {
        Moves.signal(room, "autre", 1);
        Moves.pass(room);
      }
    }

    var result = room.result().orElseThrow();

    assertThat(result.winner()).isEqualTo(team.other());
    assertThat(result.endReason()).isEqualTo(EndReason.COMPLETE);
  }

  @Test
  void aSecondReefEndsTheGameOnTheReef() {
    var team = room.state().turn();
    Moves.signal(room, "voyage", 3);
    Moves.reveal(room, Moves.indexOf(room, CardKind.REEF, 0));
    Moves.signal(room, "autre", 1);
    Moves.pass(room);
    Moves.signal(room, "encore", 1);
    Moves.reveal(room, Moves.indexOf(room, CardKind.REEF, 1));

    var result = room.result().orElseThrow();

    assertThat(result.winner()).isEqualTo(team.other());
    assertThat(result.endReason()).isEqualTo(EndReason.REEF);
  }

  @Test
  void aTeamWithoutASignaleurForTooLongForfeits() {
    var team = room.state().turn();
    var away = room.state().players().get(team.other() == Team.BABORD ? 0 : 2);
    room.disconnect(away.id(), Moves.REPLICA);
    clock.advance(Room.SIGNALER_LIMIT);
    room.expire();

    var result = room.result().orElseThrow();

    assertThat(result.winner()).isEqualTo(team);
    assertThat(result.endReason()).isEqualTo(EndReason.FORFEIT);
    assertThat(result.endedAt()).isEqualTo(clock.instant());
  }

  @Test
  void theFirstOfEachTeamIsTheSignaleurAndEveryoneIsKept() {
    Moves.win(room, Team.BABORD);

    assertThat(room.result().orElseThrow().participants())
        .extracting(GameResult.Participant::name, GameResult.Participant::role)
        .containsExactly(
            tuple("p0", Role.SIGNALER),
            tuple("p1", Role.LOOKOUT),
            tuple("p2", Role.SIGNALER),
            tuple("p3", Role.LOOKOUT));
  }

  @Test
  void aGameHasAnAccountOnlyWhenAPlayerSignedIn() {
    Moves.win(room, Team.BABORD);
    assertThat(room.result().orElseThrow().hasAccount()).isFalse();

    var account = UUID.randomUUID();
    var signedIn = Moves.startedRoom(clock, account);
    Moves.win(signedIn, Team.BABORD);

    var result = signedIn.result().orElseThrow();
    assertThat(result.hasAccount()).isTrue();
    assertThat(result.participants())
        .filteredOn(participant -> account.equals(participant.accountId()))
        .extracting(GameResult.Participant::name)
        .containsExactly("p1");
  }
}
