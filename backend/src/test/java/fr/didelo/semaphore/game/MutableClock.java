package fr.didelo.semaphore.game;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
class MutableClock extends Clock {

  private Instant now = Instant.parse("2026-10-24T10:00:00Z");

  void advance(Duration by) {
    now = now.plus(by);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
