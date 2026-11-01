-- One row per person who signed in (ADR 0011): who the provider says it is, and the name shown in the game.
-- No e-mail, no real name, no avatar. A person who signs in with both providers has two accounts.
CREATE TABLE account (
  id uuid PRIMARY KEY,
  provider text NOT NULL,
  subject text NOT NULL,
  display_name text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (provider, subject)
);

-- The games keep the account they were played for until it is forgotten (GameHistory.forget, ADR 0012), which empties this
-- column before the account goes. The partial index of V2 already serves the lookups by account.
ALTER TABLE game_player ADD CONSTRAINT game_player_account_fk FOREIGN KEY (account_id) REFERENCES account (id);
