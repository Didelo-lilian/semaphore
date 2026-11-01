package fr.didelo.semaphore.ws;

import fr.didelo.semaphore.bot.ClueGiver;
import fr.didelo.semaphore.bot.ClueGiver.Clue;
import fr.didelo.semaphore.bot.Guesser;
import fr.didelo.semaphore.bot.Level;
import fr.didelo.semaphore.game.Card;
import fr.didelo.semaphore.game.Command;
import fr.didelo.semaphore.game.IllegalMove;
import fr.didelo.semaphore.game.Phase;
import fr.didelo.semaphore.game.Room;
import fr.didelo.semaphore.game.RoomFailures;
import fr.didelo.semaphore.game.RoomRegistry;
import fr.didelo.semaphore.game.Team;
import fr.didelo.semaphore.game.Unavailable;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Plays the turns of the bots (ADR 0016). A room is handed over by a sweep, and its bots then play
 * on a virtual thread of their own, so that neither a clue nor the pace of a bot holds a thread
 * that applies the moves. A move is refused if the room has moved since the bot decided on it, so a
 * second replica, a human or a sweep that moved first leaves the bot nothing to repeat.
 */
@Component
public class BotTurns {

  // Each thread is a room being played: a replica that holds more leaves the rest to the next
  // sweep.
  private static final int MAX_ROOMS = 50;
  // A clue costs about 20 ms of one CPU: two at a time keep the rest of the replica going.
  private static final int CLUES_AT_ONCE = 2;

  private final RoomRegistry rooms;
  private final GameSocketHandler handler;
  private final ClueGiver clueGiver;
  private final Guesser guesser;
  private final Duration think;
  private final Duration pause;
  private final RandomGenerator random = RandomGenerator.getDefault();
  private final Set<String> playing = ConcurrentHashMap.newKeySet();
  private final Semaphore clues = new Semaphore(CLUES_AT_ONCE);
  private final ExecutorService executor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("bots-", 0).factory());

  public BotTurns(
      RoomRegistry rooms,
      GameSocketHandler handler,
      ClueGiver clueGiver,
      Guesser guesser,
      @Value("${semaphore.bots.think}") Duration think,
      @Value("${semaphore.bots.pause}") Duration pause) {
    this.rooms = rooms;
    this.handler = handler;
    this.clueGiver = clueGiver;
    this.guesser = guesser;
    this.think = think;
    this.pause = pause;
  }

  /** Plays the turns of the room if a bot has to act and nobody on this replica is already. */
  public void consider(Room room) {
    if (room.botToAct().isPresent() && playing.size() < MAX_ROOMS && playing.add(room.code())) {
      executor.execute(
          () -> {
            try {
              play(room.code());
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (IllegalMove | Unavailable e) {
              // The room moved on or is out of reach: the next sweep looks again.
            } catch (RuntimeException e) {
              RoomFailures.log(room.code(), "played by a bot", e);
            } finally {
              playing.remove(room.code());
            }
          });
    }
  }

  @PreDestroy
  void close() {
    executor.shutdownNow();
  }

  /** The turns of the room's bots, one after the other, until it waits for a human. */
  private void play(String code) throws InterruptedException {
    var room = rooms.find(code).orElseThrow(() -> new IllegalMove("no such room"));
    for (var bot = room.botToAct(); bot.isPresent(); bot = room.botToAct()) {
      var state = room.state();
      var level = Level.valueOf(bot.get().getBot());
      Thread.sleep(state.phase() == Phase.SIGNAL ? think : pause);
      if (state.phase() == Phase.SIGNAL) {
        var clue = clue(state.board(), state.turn(), level);
        room =
            move(
                code,
                state.lastMove(),
                bot.get().getId(),
                new Command.Signal(clue.word(), clue.number()));
        continue;
      }
      // The lookout sees the words that are hidden and the signal, nothing else.
      var hidden = state.board().stream().filter(card -> !card.revealed()).map(Card::word).toList();
      var counted = new Clue(state.signal().word(), state.revealsLeft() - 1);
      var moves = new ArrayList<Command>();
      if (counted.number() > 0) {
        guesser
            .picks(hidden, counted, level, random)
            .forEach(word -> moves.add(new Command.Reveal(indexOf(state.board(), word))));
      }
      moves.add(new Command.Pass());
      for (var command : moves) {
        room = move(code, room.state().lastMove(), bot.get().getId(), command);
        if (room.phase() != Phase.GUESS || room.state().turn() != state.turn()) {
          break;
        }
        Thread.sleep(pause);
      }
    }
  }

  private Clue clue(List<Card> board, Team team, Level level) throws InterruptedException {
    clues.acquire();
    try {
      return clueGiver.clue(board, team, level, random);
    } finally {
      clues.release();
    }
  }

  private static int indexOf(List<Card> board, String word) {
    return board.stream().map(Card::word).toList().indexOf(word);
  }

  /**
   * Applies a command of a bot if the room is still as the bot saw it, and tells the connections.
   */
  private Room move(String code, Instant seen, String botId, Command command) {
    var saved =
        rooms.change(
            code,
            room -> {
              if (!seen.equals(room.state().lastMove())) {
                throw new IllegalMove("the game moved on");
              }
              room.apply(botId, command);
            });
    handler.roomChanged(saved.room());
    return saved.room();
  }
}
