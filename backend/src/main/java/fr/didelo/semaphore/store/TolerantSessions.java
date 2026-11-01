package fr.didelo.semaphore.store;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

/**
 * What goes wrong with one session must not fail the request that met it (ADR 0010): a session that
 * cannot be read is none (the guest chooses a pseudo again), and one that was deleted since the
 * request read it is not saved back. A store that cannot be reached still fails, as the 503 of the
 * API.
 */
@Slf4j
@RequiredArgsConstructor
final class TolerantSessions<S extends Session> implements SessionRepository<S> {

  private final SessionRepository<S> sessions;

  @Override
  public S createSession() {
    return sessions.createSession();
  }

  @Override
  public void save(S session) {
    try {
      sessions.save(session);
    } catch (IllegalStateException e) {
      // Spring Session refuses to save a session whose key is gone: another request forgot the
      // guest, or it expired, meanwhile.
      log.debug("A session was deleted before it was saved: {}", e.getMessage());
    }
  }

  @Override
  public S findById(String id) {
    try {
      return sessions.findById(id);
    } catch (SerializationException | ClassCastException | IllegalStateException e) {
      log.warn("A session in Valkey cannot be read, dropped: {}", e.toString());
      sessions.deleteById(id);
      return null;
    }
  }

  @Override
  public void deleteById(String id) {
    sessions.deleteById(id);
  }
}
