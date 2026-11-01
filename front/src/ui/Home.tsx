import { Alert, Button, TextField } from "@didelo/design-system";
import { useForm } from "@tanstack/react-form";
import { useEffect } from "react";
import { z } from "zod";
import { explain } from "../reasons.ts";

interface Props {
  /** The code of the room a friend's link points to, if any. */
  invited: string | null;
  /** The pseudo the server remembers for this browser: empty at the first visit, and until it answers. */
  initialName: string;
  /** Whether a guest is remembered, to say so. */
  remembered: boolean;
  busy: boolean;
  error: string | null;
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

type Intent = "create" | "join";

export const Home = ({
  invited,
  initialName,
  remembered,
  busy,
  error,
  onCreate,
  onJoin,
  onWatch,
}: Props) => {
  const form = useForm({
    defaultValues: { name: initialName, code: "" },
    onSubmitMeta: { intent: "create" } as { intent: Intent },
    onSubmit: ({ value, meta }) => {
      const name = value.name.trim();
      if (meta.intent === "create") {
        onCreate(name);
      } else {
        onJoin(invited ?? value.code, name);
      }
    },
  });

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

      <form.Field name="name" validators={{ onChange: nameSchema }}>
        {(field) => (
          <TextField
            label="Ton nom"
            value={field.state.value}
            onChange={(event) => field.handleChange(event.target.value)}
            onBlur={field.handleBlur}
            error={field.state.meta.isTouched && !field.state.meta.isValid}
            helperText={
              field.state.meta.isTouched
                ? field.state.meta.errors.map((e) => e?.message).join(" ")
                : undefined
            }
            slotProps={{ htmlInput: { maxLength: 20 } }}
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
    </section>
  );
};
