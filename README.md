# Canapé : od3on.com sur Fire TV

Petite appli Fire TV qui ouvre **od3on.com** en plein écran et le rend utilisable à la télécommande :

- **Flèches** : passage d'un bouton ou d'une vidéo à l'autre, avec un grand cadre blanc autour de l'élément choisi. La page défile toute seule, les rangées horizontales aussi.
- **OK** : ouvre l'élément choisi (vidéo, chaîne, bouton, champ de texte avec le clavier de la Fire TV).
- **OK maintenu** : active ou coupe le **mode curseur**, une flèche de souris pour les boutons difficiles à atteindre (lecteurs vidéo intégrés, par exemple).
- **Retour** : ferme une fenêtre, sort du plein écran ou revient à la page précédente. Deux appuis sur la première page quittent l'appli.
- **Menu (≡)** : accueil, recharger, taille de l'affichage (67 % à 150 %), mode curseur, adresse de départ, aide.
- **Lecture/Pause, avance et retour rapides** : contrôlent la vidéo (± 10 s).
- L'écran reste allumé pendant une vidéo, et le son se coupe quand tu reviens à l'accueil de la Fire TV.
- Ta connexion à OD3ON est gardée d'une fois sur l'autre.

Ce n'est pas une appli officielle d'OD3ON : elle affiche simplement le site, comme un navigateur pensé pour la TV.

---

## Avant de commencer : vérifier la Fire TV

Sur la Fire TV : **Paramètres > Ma Fire TV > À propos**.

- **Fire OS** (5, 6, 7 ou 8) : c'est bon.
- **Vega OS** : impossible, ce système n'installe pas d'applis Android.

---

## Étape 1 : laisser GitHub construire l'appli (3 à 5 minutes)

Le dépôt doit rester **public**, pour que la Fire TV puisse télécharger l'appli sans mot de passe.

1. Dans le dépôt, ouvre l'onglet **Actions**. Une construction « Construire l'appli Fire TV » démarre à chaque envoi de fichiers.
2. Quand elle affiche une **coche verte**, l'appli est prête.
3. Elle apparaît à droite de la page d'accueil du dépôt, dans **Releases**. Le lien de téléchargement ne change jamais :

   `https://github.com/MrFrenchMann/OD3ON4TV/releases/latest/download/canape.apk`

> **Croix rouge ?** Clique dessus, ouvre l'étape en rouge, copie la fin du texte et envoie-la à Claude pour corriger.

## Étape 2 : créer un code court (évite de taper le lien à la télécommande)

1. Sur le PC ou le téléphone, va sur <https://go.aftvnews.com>.
2. Colle le lien de l'étape 1 et valide : tu obtiens un **code à quelques chiffres**. Note-le.

## Étape 3 : installer sur la Fire TV

1. Installe l'appli **Downloader** (de AFTVnews) depuis l'Appstore de la Fire TV.
2. Autorise-la à installer des applis :
   **Paramètres > Ma Fire TV > Options pour les développeurs > Installer des applis inconnues > Downloader : Activé**.
   *Si « Options pour les développeurs » n'apparaît pas : Paramètres > Ma Fire TV > À propos, puis appuie 7 fois sur OK sur le nom de l'appareil.*
3. Ouvre Downloader, tape ton **code** (ou le lien complet), puis **Go**.
4. Quand le téléchargement est fini : **Installer**, puis **Ouvrir**.
5. Canapé apparaît dans **Vos applis et chaînes**. Pour l'avoir sur l'accueil : sélectionne-la, appuie sur **Menu (≡)**, puis **Déplacer**.

---

## Mettre à jour plus tard

Chaque fois que tu modifies un fichier du dépôt sur GitHub (ou que tu cliques sur **Actions > Construire l'appli Fire TV > Run workflow**), une nouvelle version est construite à la **même adresse**. Sur la Fire TV, refais simplement l'étape 3 avec le même code : la mise à jour s'installe par-dessus, et ta connexion et tes réglages sont gardés.

## Personnaliser

- **Nom de l'appli** : `app/src/main/res/values/strings.xml`.
- **Icône, bannière TV et logo de démarrage** : `app/src/main/res/mipmap-*/ic_launcher.png`, `drawable-xhdpi/banner.png` (320 × 180) et `drawable-nodpi/logo.png`.
- **Page d'ouverture** : directement dans l'appli (**Menu > Adresse de départ**), sans rien reconstruire.
- **Comportement à la télécommande** (cadre blanc, défilement…) : `app/src/main/assets/tv.js`.

## Limites connues

- **Connexion avec Google** : Google refuse souvent la connexion depuis une appli de ce type. Utilise plutôt **e-mail + mot de passe**.
- **Lecteurs vidéo intégrés venant d'un autre site** : si les flèches n'atteignent pas un bouton du lecteur, passe en **mode curseur** (OK maintenu).
- **Paiements et abonnements** : ils fonctionnent dans la page, mais c'est plus simple de les faire sur le téléphone. La connexion reste la même partout.
- Si OD3ON change son site, la navigation reste générique et devrait continuer à fonctionner. Si une page pose problème, le mode curseur reste le plan B.

## Détails techniques

- Appli Android en Java, sans bibliothèque externe, pour Fire OS 5 et suivants (Android 5.1 minimum).
- Une WebView plein écran, avec une couche JavaScript injectée (`assets/tv.js`) pour la navigation à la télécommande, et les appuis simulés par l'appli (`MainActivity.java`).
- Signée avec une clé fixe (`app/canape.keystore`) pour que les mises à jour s'installent par-dessus. C'est pratique pour un usage perso, mais n'utilise pas cette clé pour une appli publiée.
