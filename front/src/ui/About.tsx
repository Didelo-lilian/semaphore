import { LegalLinks } from "./Legal.tsx";

export const About = () => (
  <section
    className="sem-card sem-about"
    data-theme="light"
    aria-labelledby="about-title"
  >
    <h2 id="about-title">Comment on joue</h2>
    <ul>
      <li>
        Deux équipes, Bâbord et Tribord, se partagent une grille de 24 mots.
      </li>
      <li>
        Chaque équipe a un Signaleur, qui voit quels mots sont les siens. Il
        envoie un mot et un nombre.
      </li>
      <li>
        Les Veilleurs retrouvent les mots de leur équipe. Un récif coûte un
        pavillon, et une équipe n'en a que deux.
      </li>
      <li>Gratuit, sans publicité ni pistage : un pseudo suffit pour jouer.</li>
    </ul>
    <LegalLinks />
  </section>
);
