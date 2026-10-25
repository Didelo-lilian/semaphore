package fr.didelo.semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import fr.didelo.semaphore.game.Cluster;
import io.lettuce.core.KillArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/** Two backends over one Valkey, as two replicas are: real contexts, real WebSocket clients. */
class RedisReplicasIT {

  private static final Duration SLOW = Duration.ofSeconds(20);

  private final List<ConfigurableApplicationContext> replicas = new ArrayList<>();
  private final List<GameClient> clients = new ArrayList<>();
  private final RedisClient redis = RedisClient.create(Valkey.url());
  private final StatefulRedisConnection<String, String> admin = redis.connect();

  @AfterEach
  void stopAll() {
    clients.forEach(GameClient::abort);
    replicas.forEach(ConfigurableApplicationContext::close);
    admin.sync().configSet("maxmemory", "64mb");
    admin.close();
    redis.shutdown();
  }

  private ConfigurableApplicationContext replica() {
    var context =
        new SpringApplicationBuilder(SemaphoreApplication.class)
            .run("--server.port=0", "--semaphore.redis-url=" + Valkey.url());
    replicas.add(context);
    return context;
  }

  private GameClient player(ConfigurableApplicationContext replica) {
    var client =
        new GameClient(replica.getEnvironment().getProperty("local.server.port", Integer.class));
    clients.add(client);
    return client;
  }

  private static String code(GameClient host) {
    return host.room().path("code").asString();
  }

  private static void send(GameClient client, String message, BooleanSupplier done) {
    client.send(message);
    await().atMost(Duration.ofSeconds(5)).until(done::getAsBoolean);
  }

  private GameClient playerIn(ConfigurableApplicationContext replica, String code, String name) {
    var client = player(replica);
    send(
        client,
        "{\"type\":\"joinRoom\",\"code\":\"" + code + "\",\"name\":\"" + name + "\"}",
        () -> client.joined() != null);
    return client;
  }

  private GameClient host(ConfigurableApplicationContext replica) {
    var host = player(replica);
    send(host, "{\"type\":\"createRoom\",\"name\":\"p0\"}", () -> host.joined() != null);
    return host;
  }

  /**
   * Four players, two per team, the game started; even ones on {@code a}, odd ones on {@code b}.
   */
  private List<GameClient> startedGame(
      ConfigurableApplicationContext a, ConfigurableApplicationContext b) {
    var players = new ArrayList<GameClient>();
    players.add(host(a));
    var code = code(players.getFirst());
    for (var i = 1; i < 4; i++) {
      players.add(playerIn(i % 2 == 0 ? a : b, code, "p" + i));
    }
    for (var i = 0; i < 4; i++) {
      var team = i < 2 ? "BABORD" : "TRIBORD";
      var player = players.get(i);
      send(
          player,
          "{\"type\":\"joinTeam\",\"team\":\"" + team + "\"}",
          () -> player.room().path("players").toString().contains("\"team\":\"" + team + "\""));
    }
    var host = players.getFirst();
    await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () ->
                host.room().path("players").valueStream().allMatch(p -> !p.path("team").isNull()));
    send(host, "{\"type\":\"start\"}", () -> !host.room().path("phase").asString().equals("LOBBY"));
    players.forEach(
        p ->
            await()
                .atMost(Duration.ofSeconds(5))
                .until(() -> !p.room().path("phase").asString().equals("LOBBY")));
    return players;
  }

  private static JsonNode player(GameClient client, int index) {
    return client.room().path("players").path(index);
  }

  @Test
  void aPlayerOnEachReplicaSeesWhatTheOtherDoesAndTheSenderKeepsItsId() {
    var a = replica();
    var b = replica();
    var host = host(a);
    var guest = playerIn(b, code(host), "bob");
    await().atMost(Duration.ofSeconds(5)).until(() -> host.room().path("players").size() == 2);

    guest.send("{\"type\":\"joinTeam\",\"id\":\"9\",\"team\":\"BABORD\"}");

    await().atMost(Duration.ofSeconds(5)).until(() -> guest.gotAnswerTo("9"));
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> player(host, 1).path("team").asString().equals("BABORD"));
    assertThat(host.gotAnswerTo("9")).isFalse();
  }

  @Test
  void theHiddenCardsAndTheTokensNeverGoOverTheChannelNorToAWatcher() {
    var a = replica();
    var b = replica();
    var published = new CopyOnWriteArrayList<String>();
    try (var subscription = redis.connectPubSub()) {
      subscription.addListener(
          new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
              published.add(message);
            }
          });
      subscription.sync().subscribe("rooms");
      var players = startedGame(a, b);
      var watcher = player(b);
      send(
          watcher,
          "{\"type\":\"watch\",\"code\":\"" + code(players.getFirst()) + "\"}",
          () -> watcher.room() != null);
      var turn = players.getFirst().room().path("turn").asString();
      var signaler = players.get(turn.equals("BABORD") ? 0 : 2);

      signaler.send("{\"type\":\"signal\",\"word\":\"qzxqzx\",\"number\":1}");

      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> watcher.room().path("phase").asString().equals("GUESS"));
      assertThat(published)
          .isNotEmpty()
          .allSatisfy(m -> assertThat(m).doesNotContain("board", "kind", "token"));
      assertThat(watcher.history)
          .allSatisfy(m -> assertThat(m.toString()).doesNotContain("\"kind\":\""));
      assertThat(signaler.room().toString()).contains("\"kind\":\"");
    }
  }

  @Test
  void aRejoinOnTheOtherReplicaTakesTheSeatAndClosesTheOldConnection() {
    var a = replica();
    var b = replica();
    var host = host(a);
    var other = playerIn(b, code(host), "bob");
    var token = host.joined().path("token").asString();
    var again = player(b);

    send(
        again,
        "{\"type\":\"rejoin\",\"code\":\"" + code(host) + "\",\"token\":\"" + token + "\"}",
        () -> again.joined() != null);

    await().atMost(Duration.ofSeconds(5)).until(() -> host.closeCode() != null);
    assertThat(host.closeReason()).isEqualTo("replaced by a newer connection");
    assertThat(again.room().path("you").asString()).isEqualTo(host.room().path("you").asString());
    // The old connection closing on its replica must not mark the player absent.
    await()
        .during(Duration.ofSeconds(1))
        .atMost(Duration.ofSeconds(3))
        .until(() -> player(other, 0).path("connected").asBoolean());
  }

  @Test
  void aReplicaThatStopsLosesNoRoomAndItsPlayersComeBackOnTheOther() {
    var a = replica();
    var b = replica();
    var players = startedGame(a, b);
    var token = players.getFirst().joined().path("token").asString();
    var code = code(players.getFirst());

    a.close();

    await().atMost(Duration.ofSeconds(10)).until(() -> players.getFirst().closeCode() != null);
    assertThat(players.getFirst().closeCode()).isEqualTo(1001);
    assertThat(players.get(2).closeCode()).isEqualTo(1001);
    assertThat(players.get(1).closeCode()).isNull();
    assertThat(players.get(1).room().path("players").valueStream())
        .allMatch(p -> p.path("connected").asBoolean());
    var back = player(b);
    send(
        back,
        "{\"type\":\"rejoin\",\"code\":\"" + code + "\",\"token\":\"" + token + "\"}",
        () -> back.joined() != null);
    assertThat(back.room().path("phase").asString())
        .isEqualTo(players.get(1).room().path("phase").asString());
  }

  @Test
  void whoDoesNotComeBackIsMarkedAbsentOnceTheHeartbeatOfTheStoppedReplicaRunsOut() {
    var a = replica();
    var b = replica();
    var players = startedGame(a, b);
    var idOfA = a.getBean(Cluster.class).id();

    a.close();
    admin.sync().del("replica:" + idOfA);

    await().atMost(SLOW).until(() -> !player(players.get(1), 0).path("connected").asBoolean());
    assertThat(player(players.get(1), 1).path("connected").asBoolean()).isTrue();
    assertThat(player(players.get(1), 2).path("connected").asBoolean()).isFalse();
    assertThat(player(players.get(1), 3).path("connected").asBoolean()).isTrue();
  }

  @Test
  void aMessageLostWhileTheSubscriptionIsDownIsCaughtUp() {
    var a = replica();
    var b = replica();
    var host = host(a);
    var guest = playerIn(b, code(host), "bob");
    await().atMost(Duration.ofSeconds(5)).until(() -> host.room().path("players").size() == 2);

    admin.sync().clientKill(KillArgs.Builder.typePubsub());
    host.send("{\"type\":\"joinTeam\",\"team\":\"BABORD\"}");

    await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> player(guest, 0).path("team").asString().equals("BABORD"));
  }

  @Test
  void aWriteThatRedisRefusesForMemoryIsRefusedCleanlyAndTheRoomSurvives() {
    var a = replica();
    var host = host(a);
    var code = code(host);

    admin.sync().configSet("maxmemory", "1");
    host.send("{\"type\":\"joinTeam\",\"id\":\"1\",\"team\":\"BABORD\"}");

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> host.gotError("the server is full, try again later"));
    assertThat(host.closeCode()).isNull();
    admin.sync().configSet("maxmemory", "64mb");
    host.send("{\"type\":\"joinTeam\",\"id\":\"2\",\"team\":\"BABORD\"}");
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> player(host, 0).path("team").asString().equals("BABORD"));
    assertThat(code(host)).isEqualTo(code);
  }
}
