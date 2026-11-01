package fr.didelo.semaphore.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.session.MapSession;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;

class TolerantSessionsTest {

  /** A store that fails the way the test says on {@code findById} and {@code save}. */
  private static final class Failing implements SessionRepository<Session> {

    RuntimeException onFind;
    RuntimeException onSave;
    final List<String> deleted = new ArrayList<>();

    @Override
    public Session createSession() {
      return new MapSession();
    }

    @Override
    public void save(Session session) {
      throw onSave;
    }

    @Override
    public Session findById(String id) {
      throw onFind;
    }

    @Override
    public void deleteById(String id) {
      deleted.add(id);
    }
  }

  private final Failing store = new Failing();
  private final TolerantSessions<Session> sessions = new TolerantSessions<>(store);

  @Test
  void aSessionThatCannotBeReadIsNoneAndIsDeleted() {
    store.onFind = new SerializationException("not json");
    assertThat(sessions.findById("a")).isNull();
    store.onFind = new ClassCastException();
    assertThat(sessions.findById("b")).isNull();

    assertThat(store.deleted).containsExactly("a", "b");
  }

  @Test
  void aStoreThatCannotBeReachedStillFails() {
    store.onFind = new QueryTimeoutException("timeout");

    assertThatThrownBy(() -> sessions.findById("a")).isInstanceOf(QueryTimeoutException.class);
  }

  @Test
  void aSessionDeletedBeforeItIsSavedIsNotAnError() {
    store.onSave = new IllegalStateException("Session was invalidated");

    sessions.save(new MapSession("a"));
  }
}
