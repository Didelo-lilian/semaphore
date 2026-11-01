import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";

const REPO = "https://github.com/Didelo-lilian/semaphore";

// Shown next to the issues link when it is not null. Set it to the address that answers requests about personal data.
const CONTACT_EMAIL: string | null = null;

export const LegalLinks = () => (
  <nav className="sem-legal-links" aria-label="Informations légales">
    <Link to="/privacy">Confidentialité</Link>
    <Link to="/terms">Conditions d'utilisation</Link>
  </nav>
);

const Contact = () => (
  <p>
    Pour une question ou une demande sur tes données, ouvre une demande sur{" "}
    <a href={`${REPO}/issues`} rel="noreferrer">
      la page des demandes du dépôt
    </a>{" "}
    (elle est publique : n'y écris pas de donnée personnelle, je te répondrai
    pour en parler autrement)
    {CONTACT_EMAIL !== null && (
      <>
        {" "}
        ou écris à <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
      </>
    )}
    .
  </p>
);

const Page = ({
  id,
  title,
  children,
}: {
  id: string;
  title: string;
  children: ReactNode;
}) => (
  <section
    className="sem-card sem-legal"
    data-theme="light"
    aria-labelledby={id}
  >
    <h1 id={id}>{title}</h1>
    {children}
    <nav className="sem-legal-links" aria-label="Navigation">
      <Link to="/">Retour à l'accueil</Link>
      <Link to={id === "privacy-title" ? "/terms" : "/privacy"}>
        {id === "privacy-title"
          ? "Conditions d'utilisation"
          : "Confidentialité"}
      </Link>
    </nav>
  </section>
);

export const PrivacyPage = () => (
  <Page id="privacy-title" title="Confidentialité">
    <p className="sem-lead">
      Sémaphore est un jeu de mots gratuit, un projet personnel sans but
      commercial. Cette page dit quelles données il traite, pourquoi, combien de
      temps, et comment les faire effacer. Pas de publicité, pas de pistage,
      aucune revente.
    </p>
    <p className="sem-hint">Dernière mise à jour : 1er novembre 2026.</p>

    <h2>Qui est responsable</h2>
    <p>
      Lilian SOLER, qui édite et gère le jeu à titre personnel. Le serveur est
      hébergé chez OVHcloud.
    </p>
    <Contact />

    <h2>Ce que le jeu garde</h2>
    <h3>Quand tu joues sans compte</h3>
    <ul>
      <li>
        Ton pseudo, ta place et tes actions dans un salon (équipe, rôle, mots
        envoyés, cartes retournées). Ils vivent dans la mémoire du serveur et
        dans une base de données en mémoire (Valkey), le temps du salon : un
        salon est effacé quand plus personne n'y est connecté depuis 30 minutes,
        et au plus tard 24 heures après sa dernière activité.
      </li>
      <li>
        Un cookie de session, <code>__Host-session</code>, qui retient ton
        pseudo d'un salon à l'autre. Il ne contient qu'un identifiant tiré au
        hasard ; le serveur y associe ton pseudo et l'oublie après 7 jours sans
        visite, et le cookie lui-même dure 30 jours au plus. Un second cookie,{" "}
        <code>__Host-XSRF-TOKEN</code>, protège tes actions contre les requêtes
        forgées depuis un autre site. Ce sont des cookies strictement
        nécessaires au jeu : il n'y a donc pas de bandeau à accepter. Tu peux
        les effacer à tout moment depuis ton navigateur.
      </li>
      <li>
        Ton navigateur garde aussi, dans le stockage de l'onglet, un jeton pour
        reprendre ta place dans le salon après un rechargement. Il disparaît
        quand tu fermes l'onglet.
      </li>
    </ul>
    <h3>Quand tu te connectes avec GitHub ou Google</h3>
    <ul>
      <li>
        Le jeu apprend seulement le nom du service et l'identifiant qu'il t'a
        donné (un numéro, pas ton adresse e-mail). Il ne garde ni e-mail, ni
        nom, ni photo, et ne conserve pas le jeton que le service lui remet.
      </li>
      <li>
        Ton compte est une ligne : un identifiant interne tiré au hasard, le
        service, ton identifiant chez lui, le pseudo que tu choisis et la date
        de création.
      </li>
      <li>
        Les parties où il y a au moins un compte sont gardées : la date de fin,
        l'équipe gagnante et, pour chaque joueur, son pseudo, son équipe, son
        rôle et son compte s'il en a un. Les parties entre invités seulement ne
        sont pas gardées. Tant que ton compte existe, ses parties sont
        conservées, sans date d'expiration.
      </li>
      <li>
        Ton profil (pseudo, parties comptées, victoires, taux de victoire) et le
        classement sont publics. Ton historique, qui montre avec qui tu as joué,
        n'est visible que de toi.
      </li>
    </ul>
    <h3>Pour la sécurité du serveur</h3>
    <ul>
      <li>
        Le serveur note chaque requête dans un journal d'accès : ton adresse IP,
        l'heure, la page demandée, la réponse et le nom de ton navigateur. Il
        sert à repérer les scans, les attaques et les abus. Il n'est pas publié,
        il tourne chaque jour et ne garde que les trois derniers jours environ.
      </li>
      <li>
        Un outil de sécurité collaboratif, CrowdSec, lit ce journal et bloque
        pour quelques heures les adresses qui attaquent le serveur. Les adresses
        reconnues comme malveillantes peuvent lui être transmises, et il fournit
        en retour une liste d'adresses à bloquer.
      </li>
    </ul>

    <h2>Ce que le jeu ne fait pas</h2>
    <ul>
      <li>
        Aucune publicité, aucun outil de mesure d'audience, aucun traceur,
        aucune revente de données.
      </li>
      <li>
        Les polices sont servies par le jeu lui-même : ton navigateur n'appelle
        aucun service tiers pour les afficher.
      </li>
      <li>
        Rien n'est envoyé à GitHub ou à Google en dehors de la connexion que tu
        demandes.
      </li>
    </ul>

    <h2>Pourquoi ces données</h2>
    <p>
      Pour faire fonctionner le jeu que tu demandes (salon, compte, historique,
      classement), et pour protéger le service contre les abus (journaux de
      sécurité, intérêt légitime).
    </p>

    <h2>Tes droits</h2>
    <p>
      Tu peux demander à voir, corriger ou effacer tes données, ou t'opposer à
      leur traitement. Changer ton pseudo corrige ton compte. Supprimer ton
      compte efface sa ligne, rend anonymes tes parties passées (ton pseudo y
      devient « un ancien joueur ») et supprime les parties où il ne reste plus
      aucun compte ; les parties des autres joueurs ne changent pas. Si tu ne
      trouves pas comment faire, demande-le par le contact ci-dessus. Tu peux
      aussi saisir la{" "}
      <a href="https://www.cnil.fr" rel="noreferrer">
        CNIL
      </a>
      .
    </p>

    <h2>Changements</h2>
    <p>
      Cette page évolue avec le jeu. La date en haut dit quand elle a changé ;
      les changements importants sont aussi dans les notes de version du dépôt.
    </p>
  </Page>
);

export const TermsPage = () => (
  <Page id="terms-title" title="Conditions d'utilisation">
    <p className="sem-lead">
      En jouant à Sémaphore, tu acceptes ces conditions. Elles sont courtes :
      joue loyalement, sois correct avec les autres.
    </p>
    <p className="sem-hint">Dernière mise à jour : 1er novembre 2026.</p>

    <h2>Le service</h2>
    <p>
      Sémaphore est un jeu de mots gratuit, un projet personnel sans but
      commercial édité par Lilian SOLER. Il est fourni tel quel : sans garantie
      de disponibilité, il peut être interrompu, modifié ou arrêté. Une mise à
      jour du serveur peut couper les parties en cours.
    </p>

    <h2>Jouer et avoir un compte</h2>
    <p>
      Tu peux jouer sans compte, avec un simple pseudo, ou avec un compte lié à
      GitHub ou à Google. Un compte est personnel. Tu choisis ton pseudo ; il
      est vu des autres joueurs du salon, et le profil et le classement des
      comptes sont publics.
    </p>

    <h2>Ce qui n'est pas permis</h2>
    <ul>
      <li>
        Les pseudos et les mots envoyés qui sont haineux, harcelants, illégaux,
        ou qui usurpent quelqu'un.
      </li>
      <li>
        Tricher : un programme qui joue à ta place, un client modifié, ou
        partager la clé de la grille hors du jeu.
      </li>
      <li>
        Chercher à nuire au service : le surcharger, le scanner, en contourner
        les protections, ou en attaquer les serveurs.
      </li>
    </ul>
    <p>
      Je peux renommer ou exclure un joueur, bloquer une adresse ou supprimer un
      compte, sans préavis, quand ces règles ne sont pas respectées.
    </p>

    <h2>Ce que tu écris</h2>
    <p>
      Tu restes responsable de ton pseudo et des mots que tu envoies. Tu
      autorises seulement le jeu à les afficher aux autres joueurs pour que la
      partie fonctionne.
    </p>

    <h2>Le code</h2>
    <p>
      Le code source de Sémaphore est publié sous licence MIT sur{" "}
      <a href={REPO} rel="noreferrer">
        GitHub
      </a>
      .
    </p>

    <h2>Responsabilité</h2>
    <p>
      Le jeu est gratuit et sans garantie. Dans la mesure permise par la loi, je
      ne suis pas responsable d'une partie perdue, d'une interruption ou d'une
      perte de données.
    </p>

    <h2>Tes données</h2>
    <p>
      La page <Link to="/privacy">Confidentialité</Link> dit ce que le jeu garde
      et comment le faire effacer.
    </p>

    <h2>Droit applicable et contact</h2>
    <p>Ces conditions relèvent du droit français.</p>
    <Contact />

    <h2>Changements</h2>
    <p>
      Ces conditions peuvent changer. La date en haut dit quand ; continuer à
      jouer après un changement vaut acceptation.
    </p>
  </Page>
);
