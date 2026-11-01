import { Alert, Button, Dialog } from "@didelo/design-system";
import { useState } from "react";

const PROVIDERS: Record<string, string> = {
  github: "GitHub",
  google: "Google",
};

/** The name of a provider the front can draw a button for, or undefined for one it does not know. */
export const providerName = (provider: string): string | undefined =>
  PROVIDERS[provider];

interface SignInProps {
  /** The providers the server says are on. */
  providers: string[];
  /** The provider of a login that stopped for want of a pseudo: the player picks it up again. */
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
  const shown = providers.filter((provider) => providerName(provider));
  const resuming = resume !== null && shown.includes(resume);
  const buttons = resuming ? [resume] : shown;
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
            {resuming ? "Continuer avec" : "Se connecter avec"}{" "}
            {providerName(provider)}
          </Button>
        ))}
      </div>
    </>
  );
};

export interface AccountProps {
  name: string;
  busy: boolean;
  failed: boolean;
  onSignOut: () => void;
  onDelete: () => void;
}

export const Account = ({
  name,
  busy,
  failed,
  onSignOut,
  onDelete,
}: AccountProps) => {
  const [confirming, setConfirming] = useState(false);
  return (
    <>
      <p className="sem-or">
        Connecté en tant que <strong>{name}</strong>
      </p>
      {failed && (
        <Alert severity="error">
          Le serveur n'a pas répondu, réessaie dans un instant.
        </Alert>
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
