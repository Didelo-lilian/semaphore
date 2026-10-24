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
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;

/**
 * One game, from the lobby to the end. It is the only place that knows the rules (ADR 0001). It is
 * not thread safe: whoever shares it synchronizes on it.
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
  private Team turn;
  private ActiveSignal signal;
  private int revealsLeft;
  private Team winner;
  // Grows with every change, so a client can drop a view that arrives after a newer one.
  private int version;
  // Set by the first sweep when nobody ever acted: a room is built to be joined right away.
  private Instant lastActivity;
  // The last command or pass of the turn: the wait for someone away starts again after each one.
  private Instant lastMove;

  public String code() {
    return code;
  }

  public Phase phase() {
    return phase;
  }

  public Optional<Player> byToken(String token) {
    return players.values().stream().filter(p -> p.getToken().equals(token)).findFirst();
  }

  public boolean abandonedFor(Duration duration) {
    if (lastActivity == null) {
      lastActivity = clock.instant();
    }
    return players.values().stream().noneMatch(Player::isConnected)
        && Duration.between(lastActivity, clock.instant()).compareTo(duration) > 0;
  }

  public Player join(String name) {
    if (phase != Phase.LOBBY) {
      throw new IllegalMove("the game has started");
    }
    if (players.size() >= MAX_PLAYERS) {
      throw new IllegalMove("the room is full");
    }
    var trimmed = name.strip();
    if (trimmed.isEmpty() || trimmed.length() > 20) {
      throw new IllegalMove("a name has 1 to 20 characters");
    }
    var player = new Player(UUID.randomUUID().toString(), UUID.randomUUID().toString(), trimmed);
    players.put(player.getId(), player);
    lastActivity = clock.instant();
    version++;
    return player;
  }

  public void disconnect(String playerId) {
    players.get(playerId).setAbsentSince(clock.instant());
    lastActivity = clock.instant();
    version++;
  }

  public void reconnect(String playerId) {
    var player = players.get(playerId);
    player.setAbsentSince(null);
    player.setLeft(false);
    lastActivity = clock.instant();
    version++;
  }

  /**
   * Leaving the lobby on purpose frees the seat at once, so nobody absent blocks the start or keeps
   * the host seat. Once the game has started the seat stays, like after a closed connection, but
   * the game does not wait for someone who left.
   */
  public void leave(String playerId) {
    if (phase != Phase.LOBBY) {
      disconnect(playerId);
      players.get(playerId).setLeft(true);
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
    var player = players.get(playerId);
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
    var now = clock.instant();
    var viewer = players.get(playerId);
    var holdsKey =
        phase != Phase.LOBBY && viewer.getTeam() != null && signaler(viewer.getTeam()) == viewer;
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
        board.stream()
            .map(
                c ->
                    new RoomView.CardView(
                        c.word(),
                        c.revealed(),
                        c.revealed() || holdsKey || phase == Phase.ENDED ? c.kind() : null))
            .toList(),
        playerId);
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
