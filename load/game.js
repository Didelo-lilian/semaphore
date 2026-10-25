// One virtual user is one table: it plays whole games back to back, with the four players and a spectator on five
// WebSockets of its own, the way the end-to-end test plays one in four browsers (ADR 0007). Virtual users cannot talk to
// each other, so a table cannot be split between several of them; the server sees the same thing either way, five
// connections and the same messages. The number of virtual users is the number of rooms playing at once.
import { WebSocket } from "k6/websockets";
import { Counter, Rate, Trend } from "k6/metrics";

const PROFILES = {
  short: { rooms: 10, duration: "30s" },
  long: { rooms: 60, duration: "5m" },
};
const PROFILE = __ENV.PROFILE || "short";
if (!PROFILES[PROFILE]) {
  throw new Error(`PROFILE is short or long, not ${PROFILE}`);
}
const ROOMS = Number(__ENV.ROOMS || PROFILES[PROFILE].rooms);
const DURATION = __ENV.DURATION || PROFILES[PROFILE].duration;
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
// first four letters (same word as the end-to-end test). A word added to the list that is too close to it makes
// every signal refused, and the test fails.
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
    command_duration: ["p(95)<1000"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

const think = () => new Promise((resolve) => setTimeout(resolve, THINK_MS * (0.5 + Math.random())));

// What answers a command, by the id it carries: a `joined` for getting into a room, a `roomUpdated` for the rest.
const ANSWERS = { createRoom: "joined", joinRoom: "joined" };

/** One connection, with the latest view of the room it got. */
class Seat {
  constructor(table, name) {
    this.table = table;
    this.name = name;
    this.view = null;
    this.viewAt = 0;
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
        const error = new Error(`${this.name}: ${reason}`);
        reject(error);
        this.fail(error);
      };
      const timer = setTimeout(() => giveUp(`no connection in ${TIMEOUT_MS} ms`), TIMEOUT_MS);
      this.socket = new WebSocket(WS_URL, null, { headers: { Origin: ORIGIN } });
      this.socket.onopen = () => {
        clearTimeout(timer);
        resolve();
      };
      this.socket.onmessage = (event) => this.receive(JSON.parse(event.data));
      this.socket.onerror = (event) => giveUp(`connection failed: ${event.error}`);
      this.socket.onclose = () => {
        if (!this.closing) {
          giveUp("connection closed by the server");
        }
      };
    });
  }

  /** Sends a command and resolves with the answer that carries its id. */
  async send(type, fields = {}) {
    await think();
    return new Promise((resolve, reject) => {
      if (this.error) {
        return reject(this.error);
      }
      const id = String(++this.lastId);
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`${this.name}: no answer to ${type} in ${TIMEOUT_MS} ms`));
      }, TIMEOUT_MS);
      const sentAt = Date.now();
      this.pending.set(id, { type, sentAt, resolve, reject, timer });
      this.table.sentAt = sentAt;
      this.socket.send(JSON.stringify({ type, id, ...fields }));
    });
  }

  /** Resolves once the view satisfies the condition: what a command changed has reached this player. */
  waitFor(condition, what) {
    return new Promise((resolve, reject) => {
      if (this.error) {
        return reject(this.error);
      }
      const waiter = { condition, resolve, reject };
      waiter.timer = setTimeout(() => {
        this.waiters.delete(waiter);
        reject(new Error(`${this.name}: ${what} not seen in ${TIMEOUT_MS} ms`));
      }, TIMEOUT_MS);
      this.waiters.add(waiter);
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
    if (request) {
      this.pending.delete(message.id);
      clearTimeout(request.timer);
      if (message.type === "error") {
        request.reject(new Error(`${this.name}: ${request.type} refused: ${message.reason}`));
      } else if (message.type !== (ANSWERS[request.type] || "roomUpdated")) {
        request.reject(new Error(`${this.name}: ${message.type} answers ${request.type}`));
      } else {
        commandDuration.add(Date.now() - request.sentAt);
        request.resolve(message);
      }
    } else if (message.type === "error") {
      this.fail(new Error(`${this.name}: error nobody asked for: ${message.reason}`));
    }
    this.settleWaiters();
  }

  settleWaiters() {
    for (const waiter of this.waiters) {
      if (this.view && waiter.condition(this.view)) {
        this.waiters.delete(waiter);
        clearTimeout(waiter.timer);
        // Only a view that arrived after the last command can be what it changed.
        if (this.viewAt >= this.table.sentAt) {
          propagationDuration.add(this.viewAt - this.table.sentAt);
        }
        waiter.resolve();
      }
    }
  }

  fail(error) {
    this.error = this.error || error;
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
    if (this.socket && this.socket.readyState === 1) {
      this.socket.send(JSON.stringify({ type: "leave" }));
    }
    if (this.socket) {
      this.socket.close();
    }
  }
}

// One game, from the creation of the room to its end: Alice (host) and Carol are Bâbord, Bob and Dave Tribord, so Alice
// and Bob are the Signaleurs, in the order they arrived. The team that starts is drawn and finds its eight words in
// three turns of the two teams, 27 commands in all.
async function playTable() {
  const table = { sentAt: 0 };
  const [alice, bob, carol, dave, spectator] = ["Alice", "Bob", "Carol", "Dave", "Spectator"].map(
    (name) => new Seat(table, name),
  );
  const seats = [alice, bob, carol, dave, spectator];
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
      await lookout.waitFor((view) => view.phase === "GUESS" && view.turn === team, "the signal");
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
    protocolErrors.add(1);
    gamesFinished.add(false);
    console.error(error.message);
  } finally {
    seats.forEach((seat) => seat.leave());
  }
}

export default playTable;

const trend = (metric) =>
  ["med", "p(90)", "p(95)", "p(99)", "max"].map((stat) => `${stat.replace(/[()]/g, "")} ${metric.values[stat]}`).join(" · ");

function report(data) {
  const { metrics } = data;
  const games = metrics.games_finished.values;
  const lines = [
    `### Load test: ${ROOMS} rooms for ${DURATION} (${PROFILE}), ${REPLICAS} replica(s), ${THINK_MS} ms to think`,
    "",
    "| | |",
    "|---|---|",
    `| Games finished | ${games.passes} of ${games.passes + games.fails} |`,
    `| Protocol errors | ${metrics.protocol_errors.values.count} |`,
    `| Connections | ${metrics.ws_sessions.values.count} |`,
    `| Command round trip, ms | ${trend(metrics.command_duration)} |`,
    `| Update reaching the next player, ms | ${trend(metrics.propagation_duration)} |`,
    "",
    "| Threshold | | |",
    "|---|---|---|",
  ];
  for (const [name, metric] of Object.entries(metrics)) {
    for (const [expression, { ok }] of Object.entries(metric.thresholds || {})) {
      lines.push(`| ${name} | ${expression} | ${ok ? "held" : "**broken**"} |`);
    }
  }
  return `${lines.join("\n")}\n`;
}

export function handleSummary(data) {
  const markdown = report(data);
  return {
    stdout: `\n${markdown}`,
    ...(RESULTS_DIR && {
      [`${RESULTS_DIR}/summary.md`]: markdown,
      [`${RESULTS_DIR}/summary.json`]: JSON.stringify(data, null, 2),
    }),
  };
}
