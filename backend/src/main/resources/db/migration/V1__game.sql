CREATE TABLE game (
  id uuid PRIMARY KEY,
  ended_at timestamptz NOT NULL,
  winner text NOT NULL CHECK (winner IN ('BABORD', 'TRIBORD'))
);
