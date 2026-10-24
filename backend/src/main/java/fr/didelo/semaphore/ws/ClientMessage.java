package fr.didelo.semaphore.ws;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import fr.didelo.semaphore.game.Team;

/**
 * What a client may send. A message is either about getting into a room or a command the room
 * judges. A field a client left out makes the message unreadable: the records refuse null.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = ClientMessage.CreateRoom.class, name = "createRoom"),
  @JsonSubTypes.Type(value = ClientMessage.JoinRoom.class, name = "joinRoom"),
  @JsonSubTypes.Type(value = ClientMessage.Rejoin.class, name = "rejoin"),
  @JsonSubTypes.Type(value = ClientMessage.JoinTeam.class, name = "joinTeam"),
  @JsonSubTypes.Type(value = ClientMessage.Start.class, name = "start"),
  @JsonSubTypes.Type(value = ClientMessage.Signal.class, name = "signal"),
  @JsonSubTypes.Type(value = ClientMessage.Reveal.class, name = "reveal"),
  @JsonSubTypes.Type(value = ClientMessage.Pass.class, name = "pass")
})
public sealed interface ClientMessage {

  record CreateRoom(String name) implements ClientMessage {
    public CreateRoom {
      requireNonNull(name);
    }
  }

  record JoinRoom(String code, String name) implements ClientMessage {
    public JoinRoom {
      requireNonNull(code);
      requireNonNull(name);
    }
  }

  /** Back into a room after a lost connection, with the token the server gave on joining. */
  record Rejoin(String code, String token) implements ClientMessage {
    public Rejoin {
      requireNonNull(code);
      requireNonNull(token);
    }
  }

  record JoinTeam(Team team) implements ClientMessage {
    public JoinTeam {
      requireNonNull(team);
    }
  }

  record Start() implements ClientMessage {}

  record Signal(String word, int number) implements ClientMessage {
    public Signal {
      requireNonNull(word);
    }
  }

  record Reveal(int index) implements ClientMessage {}

  record Pass() implements ClientMessage {}
}
