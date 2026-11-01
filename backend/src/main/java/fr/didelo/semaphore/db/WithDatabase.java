package fr.didelo.semaphore.db;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** A bean that exists only when {@code spring.datasource.url} points at a database. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Conditional(DatabaseConfigured.class)
public @interface WithDatabase {}
