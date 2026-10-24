import { Button, TextField } from "@didelo/design-system";
import { useForm } from "@tanstack/react-form";
import { z } from "zod";

const NUMBERS = [1, 2, 3, 4];

const wordSchema = z
  .string()
  .trim()
  .regex(/^\p{L}+$/u, "Un seul mot, avec des lettres.");

interface Props {
  busy: boolean;
  onSignal: (word: string, number: number) => void;
}

/** The signal of the Signaleur: one word and a number from 1 to 4. The server judges the rest. */
export const SignalForm = ({ busy, onSignal }: Props) => {
  const form = useForm({
    defaultValues: { word: "", number: 1 },
    onSubmit: ({ value }) => onSignal(value.word.trim(), value.number),
  });

  return (
    <form
      className="sem-panel sem-signal"
      data-theme="light"
      onSubmit={(event) => {
        event.preventDefault();
        form.handleSubmit();
      }}
    >
      <form.Field name="word" validators={{ onChange: wordSchema }}>
        {(field) => (
          <TextField
            label="Ton signal"
            value={field.state.value}
            onChange={(event) => field.handleChange(event.target.value)}
            onBlur={field.handleBlur}
            error={field.state.meta.isTouched && !field.state.meta.isValid}
            helperText={
              field.state.meta.isTouched
                ? field.state.meta.errors.map((e) => e?.message).join(" ")
                : "Un mot qui fait penser aux mots de ton équipe."
            }
            autoFocus
            fullWidth
          />
        )}
      </form.Field>
      <form.Field name="number">
        {(field) => (
          <fieldset className="sem-numbers" aria-label="Nombre de mots">
            {NUMBERS.map((n) => (
              <Button
                key={n}
                variant={field.state.value === n ? "contained" : "outlined"}
                aria-pressed={field.state.value === n}
                onClick={() => field.handleChange(n)}
              >
                {n}
              </Button>
            ))}
          </fieldset>
        )}
      </form.Field>
      <form.Subscribe
        selector={(state) => wordSchema.safeParse(state.values.word).success}
      >
        {(valid) => (
          <Button type="submit" variant="contained" disabled={!valid || busy}>
            Envoyer le signal
          </Button>
        )}
      </form.Subscribe>
    </form>
  );
};
