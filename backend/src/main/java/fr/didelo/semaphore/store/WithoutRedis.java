package fr.didelo.semaphore.store;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** A bean that exists only when there is no Redis: one process, rooms in its memory. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Conditional(RedisConfigured.Not.class)
@interface WithoutRedis {}
