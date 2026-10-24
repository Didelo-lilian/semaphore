package fr.didelo.semaphore.ws;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** What a client may send. Every game action added later is one more record here. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(@JsonSubTypes.Type(value = ClientMessage.Echo.class, name = "echo"))
public sealed interface ClientMessage {

  record Echo(String text) implements ClientMessage {}
}
