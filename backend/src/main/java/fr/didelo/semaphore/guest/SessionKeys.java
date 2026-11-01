package fr.didelo.semaphore.guest;

/** The attributes a session holds, plain strings only (ADR 0010: JSON, never a Java object). */
public final class SessionKeys {

  public static final String GUEST_ID = "guest.id";
  public static final String GUEST_NAME = "guest.name";

  /** Set when the browser signed in (ADR 0011); the name is then the account's. */
  public static final String ACCOUNT_ID = "account.id";

  private SessionKeys() {}
}
