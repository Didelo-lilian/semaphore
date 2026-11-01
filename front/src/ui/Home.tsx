import { Alert, Button, TextField } from "@didelo/design-system";
import { useForm } from "@tanstack/react-form";
import { useEffect, useRef } from "react";
import { z } from "zod";
import { explain } from "../reasons.ts";
import {
  Account,
  type AccountProps,
  providerName,
  SignIn,
} from "./Account.tsx";

interface SignInOptions {
  /** The providers the server says are on. */
  providers: string[];
  /** Where the sign-in sent the browser back to, if it did (ADR 0011). */
  returned: "failed" | "pseudo" | null;
  /** The provider of the login that came back for want of a pseudo. */
  resume: string | null;
  failed: boolean;
  onSignIn: (provider: string, name: string) => void;
}

interface Props {
  /** The code of the room a friend's link points to, if any. */
  invited: string | null;
  /** The pseudo the server remembers for this browser: empty at the first visit, and until it answers. */
  initialName: string;
  /** Whether a guest is remembered on this device, to say so. */
  remembered: boolean;
  busy: boolean;
  error: string | null;
  signIn: SignInOptions;
  /** Who the browser is signed in as, if it is. */
  account: AccountProps | null;
  onCreate: (name: string) => void;
  onJoin: (code: string, name: string) => void;
  onWatch: (code: string) => void;
}

const nameSchema = z
  .string()
  .trim()
  .min(1, "Dis-nous comment t'appeler.")
  .max(20, "20 caractères au plus.");
const codeSchema = z
  .string()
  .regex(/^[A-HJKMN-Z2-9]{5}$/, "Le code a 5 caractères, par exemple USQ2H.");

const cleanCode = (value: string) =>
  value.replace(/\s/g, "").toUpperCase().slice(0, 5);

type Intent = "create" | "join" | { signIn: string };

export const Home = ({
  invited,
  initialName,
  remembered,
  busy,
  error,
  signIn,
  account,
  onCreate,
  onJoin,
  onWatch,
}: Props) => {
  const signedIn = account !== null;
  const resumeName =
    signIn.resume === null ? undefined : providerName(signIn.resume);
  const form = useForm({
    defaultValues: { name: initialName, code: "" },
    onSubmitMeta: { intent: "create" } as { intent: Intent },
    onSubmit: ({ value, meta }) => {
      const name = value.name.trim();
      if (meta.intent === "create") {
        onCreate(name);
      } else if (meta.intent === "join") {
        onJoin(invited ?? value.code, name);
      } else {
        signIn.onSignIn(meta.intent.signIn, name);
      }
    },
  });

  // The name of an account that was just left (signed out, deleted) is not the next visitor's of a shared computer.
  const wasSignedIn = useRef(signedIn);
  useEffect(() => {
    if (wasSignedIn.current && !signedIn) {
      form.reset({ name: "", code: "" });
    }
    wasSignedIn.current = signedIn;
  }, [form, signedIn]);

  // The remembered name arrives after the page: it fills the field unless the player already typed.
  useEffect(() => {
    if (initialName !== "" && !form.getFieldMeta("name")?.isDirty) {
      form.setFieldValue("name", initialName, { dontUpdateMeta: true });
    }
  }, [form, initialName]);

  return (
    <section
      className="sem-card sem-home"
      data-theme="light"
      aria-labelledby="home-title"
    >
      <h1 id="home-title">Sémaphore</h1>
      <p className="sem-lead">
        Deux équipes, une grille de mots. Le Signaleur envoie un mot et un
        nombre, les Veilleurs retrouvent les bons mots sans toucher un récif.
      </p>

      {error !== null && <Alert severity="error">{explain(error)}</Alert>}
      {account === null && signIn.returned === "failed" && (
        <Alert severity="error">
          La connexion a échoué. Réessaie, ou joue sans compte.
        </Alert>
      )}
      {account === null && signIn.returned === "pseudo" && (
        <Alert severity="info">
          Dis-nous comment t'appeler, puis reprends la connexion
          {resumeName !== undefined && ` avec ${resumeName}`}.
        </Alert>
      )}
      {signIn.failed && (
        <Alert severity="error">
          Ton nom n'a pas pu être enregistré, la connexion n'a pas démarré.
          Réessaie.
        </Alert>
      )}

      <form.Field name="name" validators={{ onChange: nameSchema }}>
        {(field) => (
          <TextField
            label="Ton nom"
            value={field.state.value}
            onChange={(event) => field.handleChange(event.target.value)}
            onBlur={field.handleBlur}
            error={field.state.meta.isTouched && !field.state.meta.isValid}
            helperText={
              signedIn
                ? "Le nom de ton compte, visible des autres joueurs."
                : field.state.meta.isTouched
                  ? field.state.meta.errors.map((e) => e?.message).join(" ")
                  : undefined
            }
            slotProps={{ htmlInput: { maxLength: 20, readOnly: signedIn } }}
            autoFocus
            fullWidth
          />
        )}
      </form.Field>
      {remembered && (
        <p className="sem-hint">
          Ton nom est retenu sur cet appareil pour les prochaines parties.
        </p>
      )}

      {invited === null ? (
        <>
          <form.Subscribe selector={(state) => state.values.name.trim() !== ""}>
            {(named) => (
              <Button
                variant="contained"
                size="large"
                disabled={!named || busy}
                onClick={() => form.handleSubmit({ intent: "create" })}
              >
                Créer un salon
              </Button>
            )}
          </form.Subscribe>
          <p className="sem-or">ou rejoindre un salon</p>
          <div className="sem-join">
            <form.Field name="code">
              {(field) => (
                <TextField
                  label="Code du salon"
                  value={field.state.value}
                  onChange={(event) =>
                    field.handleChange(cleanCode(event.target.value))
                  }
                  onBlur={field.handleBlur}
                  error={
                    field.state.value !== "" &&
                    field.state.meta.isTouched &&
                    !codeSchema.safeParse(field.state.value).success
                  }
                  slotProps={{ htmlInput: { className: "sem-mono" } }}
                />
              )}
            </form.Field>
            <form.Subscribe
              selector={(state) =>
                state.values.name.trim() !== "" &&
                codeSchema.safeParse(state.values.code).success
              }
            >
              {(ready) => (
                <Button
                  variant="outlined"
                  disabled={!ready || busy}
                  onClick={() => form.handleSubmit({ intent: "join" })}
                >
                  Rejoindre
                </Button>
              )}
            </form.Subscribe>
          </div>
        </>
      ) : (
        <>
          <p>
            Tu es invité dans le salon{" "}
            <strong className="sem-mono">{invited}</strong>.
          </p>
          <form.Subscribe selector={(state) => state.values.name.trim() !== ""}>
            {(named) => (
              <Button
                variant="contained"
                size="large"
                disabled={!named || busy}
                onClick={() => form.handleSubmit({ intent: "join" })}
              >
                Rejoindre le salon
              </Button>
            )}
          </form.Subscribe>
          <p className="sem-or">ou, si la partie est lancée</p>
          <Button
            variant="outlined"
            disabled={busy}
            onClick={() => onWatch(invited)}
          >
            Regarder la partie
          </Button>
        </>
      )}

      {account !== null ? (
        <Account {...account} />
      ) : (
        invited === null && (
          <form.Subscribe selector={(state) => state.values.name.trim() !== ""}>
            {(named) => (
              <SignIn
                providers={signIn.providers}
                resume={signIn.resume}
                disabled={!named || busy}
                onSignIn={(provider) =>
                  form.handleSubmit({ intent: { signIn: provider } })
                }
              />
            )}
          </form.Subscribe>
        )
      )}
    </section>
  );
};
