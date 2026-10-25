ALTER TABLE game
  ADD COLUMN end_reason text NOT NULL DEFAULT 'COMPLETE'
    CHECK (end_reason IN ('COMPLETE', 'REEF', 'FORFEIT'));
ALTER TABLE game ALTER COLUMN end_reason DROP DEFAULT;

CREATE TABLE game_player (
  game_id uuid NOT NULL REFERENCES game (id) ON DELETE CASCADE,
  player_id uuid NOT NULL,
  name text NOT NULL,
  team text NOT NULL CHECK (team IN ('BABORD', 'TRIBORD')),
  role text NOT NULL CHECK (role IN ('SIGNALER', 'LOOKOUT')),
  account_id uuid,
  PRIMARY KEY (game_id, player_id)
);

CREATE INDEX game_player_account ON game_player (account_id) WHERE account_id IS NOT NULL;
