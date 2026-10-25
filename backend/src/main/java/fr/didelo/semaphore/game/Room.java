package fr.didelo.semaphore.game;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * One game, from the lobby to the end. It is the only place that knows the rules (ADR 0001). It is
 * not thread safe and is not shared: it is restored from a {@link RoomState}, changed by one
 * command and saved back as a new state (ADR 0008).
 */
@RequiredArgsConstructor
public final class Room {

  public static final int BOARD_SIZE = 24;
  public static final int WORDS_PER_TEAM = 8;
  public static final int CALM_WATERS = 6;
  public static final int REEFS = 2;
  public static final int FLAGS = 2;
  public static final int MAX_NUMBER = 4;
  public static final int MAX_PLAYERS = 12;
  public static final int MAX_SPECTATORS = 20;
  // Longer than a reload or the front's first retries, short enough not to wait for a ghost.
  public static final Duration LOBBY_GRACE = Duration.ofSeconds(30);
  // Longer than a reload or a bad network: only a player who stays away costs their team the turn.
  public static final Duration TURN_GRACE = Duration.ofSeconds(60);
  // A game must be able to end when a team has nobody left to give its signals.
  public static final Duration SIGNALER_LIMIT = Duration.ofMinutes(5);

  private final String code;
  private final Clock clock;
  private final RandomGenerator random;
  private final int minTeamSize;
  private final List<String> wordPool;

  private final Map<String, Player> players = new LinkedHashMap<>();
  private final Map<Team, Integer> flags =
      new EnumMap<>(Map.of(Team.BABORD, FLAGS, Team.TRIBORD, FLAGS));
  private List<Card> board = List.of();
  private Phase phase = Phase.LOBBY;
  // Spectators have no seat and no token, so the room only counts them: they never keep it alive.
  // Per replica, the one that holds their connection, so the count goes with it if it dies.
  private final Map<String, Integer> spectators = new LinkedHashMap<>();
  private Team turn;
  private ActiveSignal signal;
  private int revealsLeft;
  private Team winner;
  // Grows with every change, so a client can drop a view that arrives after a newer one.
  private int version;
  private Instant lastActivity;
  // The last command or pass of the turn: the wait for someone away starts again after each one.
  private Instant lastMove;

  /** The room as {@code state} left it, with the settings of this application. */
  public static Room restore(
      RoomState state,
      Clock clock,
      RandomGenerator random,
      int minTeamSize,
      List<String> wordPool) {
    var room = new Room(state.code(), clock, random, minTeamSize, wordPool);
    for (var p : state.players()) {
      var player = new Player(p.id(), p.token(), p.name());
      player.setTeam(p.team());
      player.setAbsentSince(p.absentSince());
      player.setLeft(p.left());
      player.setReplica(p.replica());
      room.players.put(p.id(), player);
    }
    room.flags.putAll(state.flags());
    room.board = state.board();
    room.phase = state.phase();
    room.spectators.putAll(state.spectators());
    room.turn = state.turn();
    room.signal = state.signal();
    room.revealsLeft = state.revealsLeft();
    room.winner = state.winner();
    room.version = state.version();
    room.lastActivity = state.lastActivity();
    room.lastMove = state.lastMove();
    return room;
  }

  public RoomState state() {
    return new RoomState(
        code,
        version,
        phase,
        turn,
        signal,
        revealsLeft,
        winner,
        Map.copyOf(flags),
        board,
        players.values().stream()
            .map(
                p ->
                    new RoomState.PlayerState(
                        p.getId(),
                        p.getToken(),
                        p.getName(),
                        p.getTeam(),
                        p.getAbsentSince(),
                        p.isLeft(),
                        p.getReplica()))
            .toList(),
        Map.copyOf(spectators),
        lastActivity,
        lastMove);
  }

  public String code() {
    return code;
  }

  public int version() {
    return version;
  }

  public Phase phase() {
    return phase;
  }

  public Optional<Player> byToken(String token) {
    return players.values().stream().filter(p -> p.getToken().equals(token)).findFirst();
  }

  public boolean abandonedFor(Duration duration) {
    return players.values().stream().noneMatch(Player::isConnected)
        && Duration.between(lastActivity, clock.instant()).compareTo(duration) > 0;
  }

  /** {@code replica} is the one that holds the new player's connection. */
  public Player join(String name, String replica) {
    if (phase != Phase.LOBBY) {
      throw new IllegalMove("the game has started");
    }
    if (players.size() >= MAX_PLAYERS) {
      throw new IllegalMove("the room is full");
    }
    var player =
        new Player(UUID.randomUUID().toString(), UUID.randomUUID().toString(), PlayerName.of(name));
    player.setReplica(replica);
    players.put(player.getId(), player);
    lastActivity = clock.instant();
    version++;
    return player;
  }

  /** Counts one more spectator, held by {@code replica}. A lobby has no game to watch. */
  public void watch(String replica) {
    if (phase == Phase.LOBBY) {
      throw new IllegalMove("the game has not started");
    }
    if (spectatorCount() >= MAX_SPECTATORS) {
      throw new IllegalMove("too many spectators");
    }
    spectators.merge(replica, 1, Integer::sum);
    version++;
  }

  /**
   * Sets what {@code replica} counts of the spectators, if it still counts {@code seen}: it lost
   * its count when it was judged dead while its connections were fine.
   */
  public void recountSpectators(String replica, int seen, int count) {
    if (spectators.getOrDefault(replica, 0) == seen) {
      spectators.put(replica, count);
      version++;
    }
  }

  public void unwatch(String replica) {
    // A replica judged dead has lost its count already: no entry is left to decrement.
    spectators.computeIfPresent(replica, (r, count) -> count > 1 ? count - 1 : null);
    version++;
  }

  /**
   * A connection closed. Nothing changes if the player already has another one, on another replica:
   * the old connection may close long after they took their seat back there.
   */
  public void disconnect(String playerId, String replica) {
    var player = players.get(playerId);
    // Gone already: swept as absent while this connection was still open.
    if (player != null && Objects.equals(player.getReplica(), replica)) {
      absent(player);
      version++;
    }
  }

  /** The player's connection is now held by {@code replica}. */
  public void reconnect(String playerId, String replica) {
    var player = known(playerId);
    player.setAbsentSince(null);
    player.setLeft(false);
    player.setReplica(replica);
    lastActivity = clock.instant();
    version++;
  }

  /**
   * The connection of a player the room counts absent is held by {@code replica}. Nothing changes
   * if they have left, are gone, or have taken their seat back elsewhere since the room was read.
   */
  public void reclaim(String playerId, String replica) {
    var player = players.get(playerId);
    if (player != null && player.getReplica() == null && !player.isLeft()) {
      reconnect(playerId, replica);
    }
  }

  /**
   * Marks absent every player whose connection is held by a replica that is no longer alive, and
   * forgets the spectators it held. The usual grace periods then apply. Returns whether the room
   * changed.
   */
  public boolean dropReplicasNotIn(Set<String> alive) {
    var gone =
        players.values().stream()
            .filter(p -> p.isConnected() && !alive.contains(p.getReplica()))
            .toList();
    gone.forEach(this::absent);
    var lost = spectators.keySet().removeIf(replica -> !alive.contains(replica));
    if (gone.isEmpty() && !lost) {
      return false;
    }
    version++;
    return true;
  }

  /**
   * Leaving the lobby on purpose frees the seat at once, so nobody absent blocks the start or keeps
   * the host seat. Once the game has started the seat stays, like after a closed connection, but
   * the game does not wait for someone who left.
   */
  public void leave(String playerId) {
    if (phase != Phase.LOBBY) {
      var player = known(playerId);
      absent(player);
      player.setLeft(true);
      version++;
      expire();
      return;
    }
    players.remove(playerId);
    lastActivity = clock.instant();
    version++;
  }

  /** Drops the lobby players who have been absent longer than the grace period. */
  public boolean dropAbsent() {
    var now = clock.instant();
    var dropped =
        phase == Phase.LOBBY
            && players
                .values()
                .removeIf(
                    p ->
                        !p.isConnected()
                            && Duration.between(p.getAbsentSince(), now).compareTo(LOBBY_GRACE)
                                > 0);
    if (dropped) {
      lastActivity = now;
      version++;
    }
    return dropped;
  }

  /**
   * Ends the game when a team has had no Signaleur for too long, else passes the turn once the game
   * has waited long enough for whoever is away. Returns whether the room changed.
   */
  public boolean expire() {
    if (phase != Phase.SIGNAL && phase != Phase.GUESS) {
      return false;
    }
    var now = clock.instant();
    var abandoned =
        Stream.of(Team.values())
            .map(this::signaler)
            .filter(s -> !s.isConnected() && !now.isBefore(s.getAbsentSince().plus(SIGNALER_LIMIT)))
            .min(Comparator.comparing(Player::getAbsentSince));
    if (abandoned.isPresent()) {
      winner = abandoned.get().getTeam().other();
      phase = Phase.ENDED;
    } else if (passesAt().filter(at -> !now.isBefore(at)).isPresent()) {
      endTurn();
      // The turn may have reached a team whose Signaleur left: it passes again at once.
      expire();
    } else {
      return false;
    }
    version++;
    return true;
  }

  public void apply(String playerId, Command command) {
    var player = known(playerId);
    if (!phase.allows(command)) {
      throw new IllegalMove("not possible in this phase");
    }
    lastActivity = clock.instant();
    version++;
    switch (command) {
      case Command.JoinTeam join -> player.setTeam(join.team());
      case Command.Start start -> start(player);
      case Command.Signal send -> signal(player, send);
      case Command.Reveal reveal -> reveal(player, reveal.index());
      case Command.Pass pass -> {
        requireLookout(player);
        endTurn();
      }
    }
    // Only a command that was accepted counts as a move: a refused one must not restart the wait.
    lastMove = lastActivity;
    expire();
  }

  public RoomView viewFor(String playerId) {
    return view(players.get(playerId));
  }

  /** What a lookout sees, from no seat: the same for every spectator, so it is built once. */
  public RoomView spectatorView() {
    return view(null);
  }

  private int spectatorCount() {
    return spectators.values().stream().mapToInt(Integer::intValue).sum();
  }

  /** A player can be gone while their connection is still open: swept as absent by mistake. */
  private Player known(String playerId) {
    var player = players.get(playerId);
    if (player == null) {
      throw new IllegalMove("unknown player");
    }
    return player;
  }

  private void absent(Player player) {
    player.setAbsentSince(clock.instant());
    player.setReplica(null);
    lastActivity = clock.instant();
  }

  private RoomView view(Player viewer) {
    var now = clock.instant();
    var holdsKey =
        phase != Phase.LOBBY
            && viewer != null
            && viewer.getTeam() != null
            && signaler(viewer.getTeam()) == viewer;
    var host = players.values().iterator().next();
    return new RoomView(
        code,
        version,
        phase,
        turn,
        passesAt()
            .map(at -> (int) Math.max(0, Math.ceilDiv(Duration.between(now, at).toMillis(), 1000)))
            .orElse(null),
        signal,
        revealsLeft,
        Map.copyOf(flags),
        winner,
        players.values().stream()
            .map(
                p ->
                    new RoomView.PlayerView(
                        p.getId(),
                        p.getName(),
                        p.getTeam(),
                        p.isConnected(),
                        p == host,
                        phase != Phase.LOBBY && p.getTeam() != null && signaler(p.getTeam()) == p))
            .toList(),
        spectatorCount(),
        board.stream()
            .map(
                c ->
                    new RoomView.CardView(
                        c.word(),
                        c.revealed(),
                        c.revealed() || holdsKey || phase == Phase.ENDED ? c.kind() : null))
            .toList(),
        viewer == null ? null : viewer.getId());
  }

  private void start(Player player) {
    if (player != players.values().iterator().next()) {
      throw new IllegalMove("only the host starts the game");
    }
    if (players.values().stream().anyMatch(p -> p.getTeam() == null)) {
      throw new IllegalMove("everyone must pick a team");
    }
    if (!players.values().stream().allMatch(Player::isConnected)) {
      throw new IllegalMove("everyone must be online");
    }
    for (var team : Team.values()) {
      if (members(team).size() < minTeamSize) {
        throw new IllegalMove("each team needs at least " + minTeamSize + " players");
      }
    }
    var words = new ArrayList<>(wordPool);
    Collections.shuffle(words, random);
    var kinds = new ArrayList<CardKind>();
    kinds.addAll(Collections.nCopies(WORDS_PER_TEAM, CardKind.BABORD));
    kinds.addAll(Collections.nCopies(WORDS_PER_TEAM, CardKind.TRIBORD));
    kinds.addAll(Collections.nCopies(CALM_WATERS, CardKind.CALM));
    kinds.addAll(Collections.nCopies(REEFS, CardKind.REEF));
    Collections.shuffle(kinds, random);
    board =
        IntStream.range(0, BOARD_SIZE)
            .mapToObj(i -> new Card(words.get(i), kinds.get(i), false))
            .toList();
    turn = Team.values()[random.nextInt(Team.values().length)];
    phase = Phase.SIGNAL;
  }

  private void signal(Player player, Command.Signal send) {
    if (player != signaler(turn)) {
      throw new IllegalMove("only the Signaleur of the team in play can signal");
    }
    if (send.number() < 1 || send.number() > MAX_NUMBER) {
      throw new IllegalMove("the number is from 1 to " + MAX_NUMBER);
    }
    var word = normalize(send.word());
    if (!word.matches("[a-z]+(-[a-z]+)*") || word.length() > 30) {
      throw new IllegalMove("a signal is one word, letters only");
    }
    for (var card : board) {
      var other = normalize(card.word());
      var sharesStart =
          word.length() >= 4
              && other.length() >= 4
              && word.substring(0, 4).equals(other.substring(0, 4));
      var contains =
          (other.length() >= 3 && word.contains(other))
              || (word.length() >= 3 && other.contains(word));
      if (!card.revealed() && (word.equals(other) || contains || sharesStart)) {
        throw new IllegalMove("the signal is too close to a word on the grid");
      }
    }
    signal = new ActiveSignal(send.word().strip(), send.number());
    revealsLeft = send.number() + 1;
    phase = Phase.GUESS;
  }

  private void reveal(Player player, int index) {
    requireLookout(player);
    if (index < 0 || index >= BOARD_SIZE || board.get(index).revealed()) {
      throw new IllegalMove("pick a word that is still hidden");
    }
    var card = board.get(index).reveal();
    var cards = new ArrayList<>(board);
    cards.set(index, card);
    board = List.copyOf(cards);
    revealsLeft--;
    for (var team : Team.values()) {
      if (board.stream().filter(c -> c.kind() == team.kind()).allMatch(Card::revealed)) {
        winner = team;
        phase = Phase.ENDED;
        return;
      }
    }
    if (card.kind() == CardKind.REEF) {
      flags.merge(turn, -1, Integer::sum);
      if (flags.get(turn) == 0) {
        winner = turn.other();
        phase = Phase.ENDED;
        return;
      }
    }
    if (card.kind() != turn.kind() || revealsLeft == 0) {
      endTurn();
    }
  }

  /**
   * When the turn passes for lack of anyone to play it, empty while the game waits for nobody away.
   * The Signaleur is waited for during {@code SIGNAL}, the lookouts during {@code GUESS}; someone
   * who left is not, unless the other team's Signaleur left too: the turn would bounce between two
   * teams that nobody can play for.
   */
  private Optional<Instant> passesAt() {
    if (phase != Phase.SIGNAL && phase != Phase.GUESS) {
      return Optional.empty();
    }
    var awaited =
        phase == Phase.SIGNAL ? List.of(signaler(turn)) : members(turn).stream().skip(1).toList();
    if (awaited.stream().anyMatch(Player::isConnected)) {
      return Optional.empty();
    }
    var away =
        awaited.stream().map(Player::getAbsentSince).max(Comparator.naturalOrder()).orElseThrow();
    var since = away.isAfter(lastMove) ? away : lastMove;
    var atOnce = awaited.stream().allMatch(Player::isLeft) && !signaler(turn.other()).isLeft();
    return Optional.of(since.plus(atOnce ? Duration.ZERO : TURN_GRACE));
  }

  private void endTurn() {
    lastMove = clock.instant();
    turn = turn.other();
    signal = null;
    revealsLeft = 0;
    phase = Phase.SIGNAL;
  }

  private void requireLookout(Player player) {
    if (player.getTeam() != turn || player == signaler(turn)) {
      throw new IllegalMove("only the lookouts of the team in play can do that");
    }
  }

  private List<Player> members(Team team) {
    return players.values().stream().filter(p -> p.getTeam() == team).toList();
  }

  /**
   * The Signaleur is the first member of the team in the order they joined the room, for the whole
   * grid: someone who has seen the key cannot be a lookout on the same grid.
   */
  private Player signaler(Team team) {
    return members(team).get(0);
  }

  static String normalize(String text) {
    // œ and æ do not decompose into letters, so they are spelled out.
    return Normalizer.normalize(text.strip(), Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(java.util.Locale.ROOT)
        .replace("œ", "oe")
        .replace("æ", "ae");
  }
}
