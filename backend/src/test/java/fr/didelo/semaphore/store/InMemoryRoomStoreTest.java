package fr.didelo.semaphore.store;

import fr.didelo.semaphore.game.RoomStore;

class InMemoryRoomStoreTest extends RoomStoreContract {

  private final RoomStore store = new InMemoryRoomStore();

  @Override
  RoomStore store() {
    return store;
  }
}
