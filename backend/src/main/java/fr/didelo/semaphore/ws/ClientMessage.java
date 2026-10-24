package fr.didelo.semaphore.ws;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import fr.didelo.semaphore.game.Team;

/**
 * What a client may send. A message is either about getting into a room or a command the room
 * judges. A field a client left out makes the message unreadable: the records refuse null, except
 * the optional {@code id}, which the server echoes on its answer to that message.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ClientMessage.CreateRoom.class, name = "createRoom"),
  @JsonSubTypes.Type(value = ClientMessage.JoinRoom.class, name = "joinRoom"),
  @JsonSubTypes.Type(value = ClientMessage.Rejoin.class, name = "rejoin"),
  @JsonSubTypes.Type(value = ClientMessage.Leave.class, name = "leave"),
  @JsonSubTypes.Type(value = ClientMessage.JoinTeam.class, name = "joinTeam"),
  @JsonSubTypes.Type(value = ClientMessage.Start.class, name = "start"),
  @JsonSubTypes.Type(value = ClientMessage.Signal.class, name = "signal"),
  @JsonSubTypes.Type(value = ClientMessage.Reveal.class, name = "reveal"),
  @JsonSubTypes.Type(value = ClientMessage.Pass.class, name = "pass")
})
public sealed interface ClientMessage {

  String id();

  record CreateRoom(String id, String name) implements ClientMessage {
    public CreateRoom {
      requireNonNull(name);
    }
  }

  record JoinRoom(String id, String code, String name) implements ClientMessage {
    public JoinRoom {
      requireNonNull(code);
      requireNonNull(name);
    }
  }

  /** Back into a room after a lost connection, with the token the server gave on joining. */
  record Rejoin(String id, String code, String token) implements ClientMessage {
    public Rejoin {
      requireNonNull(code);
      requireNonNull(token);
    }
  }

  /** Leaving a room on purpose. It has no answer: the player is no longer there to read it. */
  record Leave(String id) implements ClientMessage {}

  record JoinTeam(String id, Team team) implements ClientMessage {
    public JoinTeam {
      requireNonNull(team);
    }
  }

  record Start(String id) implements ClientMessage {}

  record Signal(String id, String word, int number) implements ClientMessage {
    public Signal {
      requireNonNull(word);
    }
  }

  record Reveal(String id, int index) implements ClientMessage {}

  record Pass(String id) implements ClientMessage {}
}
