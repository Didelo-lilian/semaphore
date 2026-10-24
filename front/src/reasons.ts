/** The server's refusals are English identifiers of its rules; players read them in French. */
const EXACT: Record<string, string> = {
  "no such room": "Ce salon n'existe pas, ou plus.",
  "the game has started": "La partie a déjà commencé.",
  "the room is full": "Le salon est complet.",
  "a name has 1 to 20 characters": "Un nom fait de 1 à 20 caractères.",
  "only the host starts the game": "Seul l'hôte lance la partie.",
  "everyone must pick a team": "Tout le monde doit choisir une équipe.",
  "you are already in a room": "Tu es déjà dans un salon.",
  "join a room first": "Rejoins d'abord un salon.",
  "unknown player": "Ta place a disparu : rejoins le salon.",
  "not possible in this phase": "Impossible à ce moment de la partie.",
  "unreadable message": "Message illisible.",
  "connection lost": "Connexion perdue, réessaie.",
  "unreadable message from the server": "Réponse illisible du serveur.",
  "unexpected message from the server": "Réponse inattendue du serveur.",
  "only the Signaleur of the team in play can signal":
    "Seul le Signaleur de l'équipe qui joue peut envoyer un signal.",
  "a signal is one word, letters only":
    "Un signal est un seul mot, avec des lettres.",
  "the signal is too close to a word on the grid":
    "Ce signal est trop proche d'un mot de la grille.",
  "pick a word that is still hidden": "Choisis un mot encore caché.",
  "only the lookouts of the team in play can do that":
    "Seuls les Veilleurs de l'équipe qui joue peuvent faire ça.",
};

export const explain = (reason: string): string => {
  const known = EXACT[reason];
  if (known !== undefined) {
    return known;
  }
  const size = /^each team needs at least (\d+) players$/.exec(reason);
  if (size !== null) {
    return `Chaque équipe a besoin d'au moins ${size[1]} joueurs.`;
  }
  const range = /^the number is from (\d+) to (\d+)$/.exec(reason);
  if (range !== null) {
    return `Le nombre va de ${range[1]} à ${range[2]}.`;
  }
  return reason;
};
