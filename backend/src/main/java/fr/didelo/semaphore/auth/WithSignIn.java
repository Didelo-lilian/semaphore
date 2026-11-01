package fr.didelo.semaphore.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Conditional;

/** A bean that exists only when a player can sign in: a database and one complete provider. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Conditional(SignInConfigured.class)
public @interface WithSignIn {}
