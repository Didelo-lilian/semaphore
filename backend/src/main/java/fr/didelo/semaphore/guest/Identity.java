package fr.didelo.semaphore.guest;

/**
 * The names under which the session of ADR 0010 keeps who someone is, plain strings so that a
 * session written by one version of the backend is read by the next. The WebSocket handshake copies
 * them into the socket's attributes; a client never sends them.
 */
public final class Identity {

  public static final String ID = "guest.id";
  public static final String NAME = "guest.name";
  // Written by sign-in, as the string of the account's UUID; absent for a guest.
  public static final String ACCOUNT_ID = "account.id";

  private Identity() {}
}
