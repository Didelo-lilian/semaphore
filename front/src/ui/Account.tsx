import { Alert, Button, Dialog } from "@didelo/design-system";
import { useState } from "react";

const PROVIDERS: Record<string, string> = {
  github: "GitHub",
  google: "Google",
};

/** The name of a provider the front can draw a button for, or undefined for one it does not know. */
export const providerName = (provider: string): string | undefined =>
  Object.hasOwn(PROVIDERS, provider) ? PROVIDERS[provider] : undefined;

interface SignInProps {
  /** The providers that are on and that the front knows. */
  providers: string[];
  /** One of them, the one of a login that stopped for want of a pseudo: the player picks it up again. */
  resume: string | null;
  disabled: boolean;
  onSignIn: (provider: string) => void;
}

export const SignIn = ({
  providers,
  resume,
  disabled,
  onSignIn,
}: SignInProps) => {
  const buttons = resume === null ? providers : [resume];
  return buttons.length === 0 ? null : (
    <>
      <p className="sem-or">ou se connecter pour retrouver ton compte</p>
      <div className="sem-sign-in">
        {buttons.map((provider) => (
          <Button
            key={provider}
            variant="outlined"
            disabled={disabled}
            onClick={() => onSignIn(provider)}
          >
            {resume === null ? "Se connecter avec" : "Continuer avec"}{" "}
            {providerName(provider)}
          </Button>
        ))}
      </div>
    </>
  );
};

/** What the server did not do: `status` is its refusal, or null when it did not answer. */
export interface Failure {
  action: "signOut" | "delete";
  status: number | null;
}

export interface AccountProps {
  name: string;
  busy: boolean;
  failure: Failure | null;
  onSignOut: () => void;
  onDelete: () => void;
}

const explainFailure = ({ action, status }: Failure) => {
  const undone =
    action === "delete"
      ? "ton compte n'est pas supprimé"
      : "tu es toujours connecté";
  return status === null
    ? `Le serveur n'a pas répondu : ${undone}. Réessaie dans un instant.`
    : `Le serveur a refusé (code ${status}) : ${undone}. Réessaie plus tard.`;
};

export const Account = ({
  name,
  busy,
  failure,
  onSignOut,
  onDelete,
}: AccountProps) => {
  const [confirming, setConfirming] = useState(false);
  return (
    <>
      <p className="sem-or">
        Connecté en tant que <strong>{name}</strong>
      </p>
      {failure !== null && (
        <Alert severity="error">{explainFailure(failure)}</Alert>
      )}
      <div className="sem-sign-in">
        <Button variant="outlined" disabled={busy} onClick={onSignOut}>
          Se déconnecter
        </Button>
        <Button
          variant="text"
          color="error"
          disabled={busy}
          onClick={() => setConfirming(true)}
        >
          Supprimer mon compte
        </Button>
      </div>

      <Dialog
        open={confirming}
        title="Supprimer ton compte ?"
        onClose={() => setConfirming(false)}
        actions={
          <>
            <Button variant="text" onClick={() => setConfirming(false)}>
              Garder mon compte
            </Button>
            <Button
              variant="contained"
              color="error"
              disabled={busy}
              onClick={() => {
                setConfirming(false);
                onDelete();
              }}
            >
              Supprimer
            </Button>
          </>
        }
      >
        Ton compte est effacé pour de bon. Dans l'historique des autres joueurs,
        tu deviens « un ancien joueur » ; les parties où tu étais le seul compte
        disparaissent.
      </Dialog>
    </>
  );
};
