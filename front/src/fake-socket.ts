/** A WebSocket the tests drive by hand. */
export class FakeSocket {
  static all: FakeSocket[] = [];
  static last = () => FakeSocket.all[FakeSocket.all.length - 1] as FakeSocket;

  readyState = 0;
  sent: ({ id?: string } & Record<string, unknown>)[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: ((event: { reason: string }) => void) | null = null;

  constructor() {
    FakeSocket.all.push(this);
  }

  send(data: string) {
    this.sent.push(JSON.parse(data));
  }

  close() {
    this.readyState = 3;
  }

  accept() {
    this.readyState = WebSocket.OPEN;
    this.onopen?.();
  }

  receive(message: unknown) {
    this.onmessage?.({ data: JSON.stringify(message) });
  }

  /** The server's answer to a message sent (the last one by default): it carries that message's id. */
  answer(message: object, to = this.sent.length - 1) {
    this.receive({ ...message, id: this.sent[to]?.id });
  }

  drop(reason = "") {
    this.readyState = 3;
    this.onclose?.({ reason });
  }
}
