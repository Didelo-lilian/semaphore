// One virtual user is one table: it plays whole games back to back, with the four players and a spectator on five
// WebSockets of its own, the way the end-to-end test plays one in four browsers (ADR 0007). Virtual users cannot talk to
// each other, so a table cannot be split between several of them; the server sees the same thing either way, five
// connections and the same messages. The number of virtual users is the number of rooms playing at once.
import { WebSocket } from "k6/websockets";
import { Counter, Rate, Trend } from "k6/metrics";

// The p95 threshold of a command is ten times the worst p95 measured on a CI runner with these rooms (README, "Thresholds").
const PROFILES = {
  short: { rooms: 50, duration: "30s", p95: 50 },
  long: { rooms: 100, duration: "5m", p95: 150 },
};
const PROFILE = __ENV.PROFILE || "short";
if (!PROFILES[PROFILE]) {
  throw new Error(`PROFILE is short or long, not ${PROFILE}`);
}
const ROOMS = Number(__ENV.ROOMS || PROFILES[PROFILE].rooms);
const DURATION = __ENV.DURATION || PROFILES[PROFILE].duration;
const P95_MS = Number(__ENV.P95_MS || PROFILES[PROFILE].p95);
// The pause before each command, 0.5 to 1.5 times this: a player reads the table before acting.
const THINK_MS = Number(__ENV.THINK_MS || 100);
const WS_URL = __ENV.WS_URL || "ws://localhost:8080/ws";
// Browsers send the page's origin and the backend refuses the others (SEMAPHORE_ALLOWED_ORIGINS).
const ORIGIN = __ENV.ORIGIN || "http://load.test";
const REPLICAS = __ENV.REPLICAS || "1";
const RESULTS_DIR = __ENV.RESULTS_DIR;
// The longest wait for an answer, or for what a command changes in another player's view.
const TIMEOUT_MS = 10_000;

if (/didelo\.fr/i.test(WS_URL)) {
  throw new Error("this test never runs against production: start a backend with load/compose.yaml");
}

// Valid whatever the random grid: none of the 160 words of words.txt is contained in it, contains it or shares its
// first four letters (same word as the end-to-end test). A word added to the list that is too close to it is refused
// on the grids that draw it, and the test fails.
const SIGNAL = "voyage";
// The highest number a signal can carry (MAX_NUMBER in Room.java).
const MAX_NUMBER = 4;

const commandDuration = new Trend("command_duration", true);
const propagationDuration = new Trend("propagation_duration", true);
const protocolErrors = new Counter("protocol_errors");
const gamesFinished = new Rate("games_finished");

export const options = {
  scenarios: {
    tables: {
      executor: "constant-vus",
      vus: ROOMS,
      duration: DURATION,
      // A game that has started is played to its end after the duration is over.
      gracefulStop: "60s",
    },
  },
  thresholds: {
    protocol_errors: ["count==0"],
    games_finished: ["rate==1"],
    command_duration: [`p(95)<${P95_MS}`],
  },
  summaryTrendStats: ["count", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

// What answers a command, by the id it carries: a `joined` for getting into a room, a `roomUpdated` for the rest.
const ANSWERS = { createRoom: "joined", joinRoom: "joined" };

/** One connection, with the latest view of the room it got. */
class Seat {
  constructor(name) {
    this.name = name;
    this.view = null;
    this.viewAt = 0;
    this.sentAt = 0;
    this.lastId = 0;
    this.pending = new Map();
    this.waiters = new Set();
    this.error = null;
    this.closing = false;
  }

  open() {
    return new Promise((resolve, reject) => {
      // Rejects while connecting, and fails what is waiting once the connection is open.
      const giveUp = (reason) => {
        clearTimeout(timer);
        // The game is over and this connection was closed on purpose: nothing it says counts.
        if (this.closing) {
          return;
        }
        const error = new Error(`${this.name}: ${reason}`);
        reject(error);
        this.fail(error);
      };
      const timer = setTimeout(() => giveUp(`no connection in ${TIMEOUT_MS} ms`), TIMEOUT_MS);
      this.socket = new WebSocket(WS_URL, null, { headers: { Origin: ORIGIN } });
      this.socket.onopen = () => {
        clearTimeout(timer);
        resolve();
        // A game that failed while this connection was opening is over: nobody will close it.
        if (this.closing) {
          this.socket.close();
        }
      };
      this.socket.onmessage = (event) => {
        // k6 only logs an exception thrown in a callback and ends the iteration: counted, it fails the game instead.
        try {
          this.receive(JSON.parse(event.data));
        } catch (error) {
          this.fail(error);
        }
      };
      this.socket.onerror = (event) => giveUp(`connection failed: ${event.error}`);
      this.socket.onclose = () => giveUp("connection closed by the server");
    });
  }

  /** Sends a command and resolves with the answer that carries its id. */
  async send(type, fields = {}) {
    await pause(THINK_MS * (0.5 + Math.random()));
    return new Promise((resolve, reject) => {
      if (this.error) {
        return reject(this.error);
      }
      const id = String(++this.lastId);
      const timer = setTimeout(
        () => this.fail(new Error(`${this.name}: no answer to ${type} in ${TIMEOUT_MS} ms`)),
        TIMEOUT_MS,
      );
      this.sentAt = Date.now();
      this.pending.set(id, { type, sentAt: this.sentAt, resolve, reject, timer });
      this.socket.send(JSON.stringify({ type, id, ...fields }));
    });
  }

  /** Resolves, with the time the view arrived, once it satisfies the condition: what a command changed has reached this player. */
  waitFor(condition, what) {
    return new Promise((resolve, reject) => {
      if (this.error) {
        return reject(this.error);
      }
      const timer = setTimeout(
        () => this.fail(new Error(`${this.name}: ${what} not seen in ${TIMEOUT_MS} ms`)),
        TIMEOUT_MS,
      );
      this.waiters.add({ condition, resolve, reject, timer });
      this.settleWaiters();
    });
  }

  receive(message) {
    if (this.closing) {
      return;
    }
    if (message.room && (!this.view || message.room.version >= this.view.version)) {
      this.view = message.room;
      this.viewAt = Date.now();
    }
    const request = this.pending.get(message.id);
    if (message.type === "error") {
      this.fail(
        new Error(
          request
            ? `${this.name}: ${request.type} refused: ${message.reason}`
            : `${this.name}: error nobody asked for: ${message.reason}`,
        ),
      );
    } else if (request && message.type !== (ANSWERS[request.type] || "roomUpdated")) {
      this.fail(new Error(`${this.name}: ${message.type} answers ${request.type}`));
    } else if (request) {
      this.pending.delete(message.id);
      clearTimeout(request.timer);
      commandDuration.add(Date.now() - request.sentAt);
      request.resolve(message);
    }
    this.settleWaiters();
  }

  settleWaiters() {
    for (const waiter of this.waiters) {
      if (waiter.condition(this.view)) {
        this.waiters.delete(waiter);
        clearTimeout(waiter.timer);
        waiter.resolve(this.viewAt);
      }
    }
  }

  /** The server broke the protocol with this connection: counted and said once, whatever was waiting is rejected. */
  fail(error) {
    if (!this.error) {
      this.error = error;
      protocolErrors.add(1);
      console.error(error.message);
    }
    this.settle(this.error);
  }

  /** Rejects what is still waiting and clears its timers, which would keep the iteration alive. */
  settle(error) {
    for (const request of this.pending.values()) {
      clearTimeout(request.timer);
      request.reject(error);
    }
    for (const waiter of this.waiters) {
      clearTimeout(waiter.timer);
      waiter.reject(error);
    }
    this.pending.clear();
    this.waiters.clear();
  }

  /** Goes home as the front does: `leave`, then the connection closes. */
  leave() {
    this.closing = true;
    this.settle(new Error(`${this.name} left`));
    // Closing a connection that is still opening never lets k6 end the iteration: onopen closes it.
    if (this.socket?.readyState === 1) {
      this.socket.send(JSON.stringify({ type: "leave" }));
      this.socket.close();
    }
  }
}

// One game, from the creation of the room to its end: Alice (host) and Carol are Bâbord, Bob and Dave Tribord, so Alice
// and Bob are the Signaleurs, in the order they arrived. The team that starts is drawn and finds its eight words in
// three turns of the two teams, 27 commands in all.
async function playTable() {
  const seats = ["Alice", "Bob", "Carol", "Dave", "Spectator"].map((name) => new Seat(name));
  const [alice, bob, carol, dave, spectator] = seats;
  const crews = {
    BABORD: { signaler: alice, lookout: carol },
    TRIBORD: { signaler: bob, lookout: dave },
  };
  try {
    await alice.open();
    const { room } = await alice.send("createRoom", { name: alice.name });
    for (const guest of [bob, carol, dave]) {
      await guest.open();
      await guest.send("joinRoom", { code: room.code, name: guest.name });
    }
    for (const [seat, team] of [[alice, "BABORD"], [bob, "TRIBORD"], [carol, "BABORD"], [dave, "TRIBORD"]]) {
      await seat.send("joinTeam", { team });
    }
    await alice.send("start");
    await spectator.open();
    await spectator.send("watch", { code: room.code });

    let team = alice.view.turn;
    for (;;) {
      const { signaler, lookout } = crews[team];
      await signaler.waitFor((view) => view.phase === "SIGNAL" && view.turn === team, `the turn of ${team}`);
      // The Signaleur's view holds the key: the words of the team that are still hidden.
      const words = signaler.view.board
        .flatMap((card, index) => (card.kind === team && !card.revealed ? [index] : []))
        .slice(0, MAX_NUMBER);
      await signaler.send("signal", { word: SIGNAL, number: words.length });
      const seenAt = await lookout.waitFor((view) => view.phase === "GUESS" && view.turn === team, "the signal");
      propagationDuration.add(seenAt - signaler.sentAt);
      for (const index of words) {
        await lookout.send("reveal", { index });
      }
      if (lookout.view.phase === "ENDED") {
        break;
      }
      await lookout.send("pass");
      team = team === "BABORD" ? "TRIBORD" : "BABORD";
    }
    await Promise.all(
      seats.map((seat) => seat.waitFor((view) => view.phase === "ENDED" && Boolean(view.winner), "the winner")),
    );
    gamesFinished.add(true);
  } catch (error) {
    gamesFinished.add(false);
    // A connection that failed has counted the error and said why: anything else is the script's own.
    if (!seats.some((seat) => seat.error === error)) {
      console.error(error.stack);
    }
    // A table starts again a second later, as the front reconnects, instead of hammering a backend that refuses it.
    await pause(1000);
  } finally {
    seats.forEach((seat) => seat.leave());
  }
}

export default playTable;

// k6 reports a Trend that has a threshold even without a sample, with every value at 0.
const trend = (metric) =>
  metric?.values.count
    ? ["med", "p(90)", "p(95)", "p(99)", "max"]
        .map((stat) => `${stat.replace(/[()]/g, "")} ${Math.round(metric.values[stat])}`)
        .join(" · ")
    : "no sample";

export function handleSummary(data) {
  const { metrics } = data;
  const games = metrics.games_finished.values;
  const lines = [
    `### Load test: ${ROOMS} rooms for ${DURATION} (${PROFILE}), ${REPLICAS} replica(s), ${THINK_MS} ms to think`,
    "",
    "| | |",
    "|---|---|",
    `| Games finished | ${games.passes} of ${games.passes + games.fails} |`,
    `| Protocol errors | ${metrics.protocol_errors.values.count} |`,
    `| Command round trip, ms | ${trend(metrics.command_duration)} |`,
    `| Signal reaching the lookouts, ms | ${trend(metrics.propagation_duration)} |`,
    "",
    "| Threshold | | |",
    "|---|---|---|",
  ];
  for (const [name, metric] of Object.entries(metrics)) {
    for (const [expression, { ok }] of Object.entries(metric.thresholds || {})) {
      lines.push(`| ${name} | ${expression} | ${ok ? "held" : "**broken**"} |`);
    }
  }
  const markdown = `${lines.join("\n")}\n`;
  return {
    stdout: `\n${markdown}`,
    ...(RESULTS_DIR && {
      [`${RESULTS_DIR}/summary.md`]: markdown,
      [`${RESULTS_DIR}/summary.json`]: JSON.stringify(data, null, 2),
    }),
  };
}
