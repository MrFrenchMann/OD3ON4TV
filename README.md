# Odeon : od3on.com sur Fire TV

Petite appli Fire TV qui ouvre **od3on.com** en plein écran et le rend utilisable à la télécommande :

- **Flèches** : passage d'un bouton ou d'une vidéo à l'autre, avec un léger surlignement blanc sur l'élément choisi. La page défile toute seule, les rangées horizontales aussi. Sur un lecteur vidéo, droite et gauche atteignent les boutons posés sur l'image.
- **OK** : ouvre l'élément choisi (vidéo, chaîne, bouton, champ de texte avec le clavier de la Fire TV).
- **OK maintenu** : active ou coupe le **mode curseur**, une flèche de souris pour les boutons difficiles à atteindre (lecteurs vidéo intégrés, par exemple).
- **Retour** : ferme une fenêtre, sort du plein écran ou revient à la page précédente. Sur l'accueil, deux appuis quittent l'appli ; trois appuis rapides quittent depuis n'importe où.
- **Menu (≡)** : Accueil, **Suivis**, **Mettre à jour l'appli**, Recharger, Taille de l'affichage (◀ ▶, de 67 % à 150 %), Lecteur TV, Mode curseur, Adresse de départ, Aide, Quitter. Quand une nouvelle version existe, l'appli le propose aussi au lancement (« Installer » / « Plus tard »).
- **Suivis** : le menu se ferme et la page « Suivis » d'OD3ON s'ouvre en plein écran (il faut être connecté). L'appli trouve l'onglet toute seule la première fois, puis retient son adresse.
- **Lecteur TV** : quand tu lances une vidéo ou un podcast, l'appli l'ouvre dans son propre lecteur plein écran, comme Netflix (la page du site ne lit plus rien : fini l'écran noir et la page figée). OK : pause / lecture ; ◀ ▶ : ± 10 s ; Menu (≡) : image dans l'image ; Retour : revenir à la page, au même moment de la vidéo. Pour un podcast, l'image du podcast s'affiche en grand. Si le lecteur n'arrive pas à lire un contenu, la lecture repart dans la page. Se désactive dans Menu (≡) > Lecteur TV.
- **Reprise là où tu t'es arrêté, même sur un autre appareil** : le lecteur TV démarre à la position qu'OD3ON a gardée. Au retour, l'appli rouvre la page de la vidéo et la place à l'endroit atteint pour qu'OD3ON l'enregistre (sur la TV, la position est aussi gardée en secours).
- **Image figée** : pendant la lecture, la page du site est vidée pour laisser toute la mémoire au lecteur ; qualité plafonnée à la Full HD ; si l'image se fige, le lecteur relance la vidéo au même endroit, puis baisse la qualité si besoin.
- **Barre latérale du site** : quand tu choisis une section dans la barre verticale de gauche, elle disparaît et la page prend toute la largeur. Elle ne revient que si tu appuies sur l'icône ☰ en haut à gauche.
- **En cas de problème** : l'appli affiche le code de l'erreur (et, si elle s'est fermée, le détail au lancement suivant). Prends l'écran en photo et envoie-le à Claude.
- **Image dans l'image** (sans le lecteur TV) : un bouton « Image dans l'image » apparaît en haut à droite de la vidéo (en bas à droite de l'écran pour un podcast). La vidéo continue dans une petite fenêtre (pour un podcast, c'est son image qui s'affiche), et tu peux naviguer sur la Fire TV sans que la lecture s'arrête. Une fois le bouton utilisé, le bouton **Accueil** de la télécommande garde aussi la lecture dans la petite fenêtre. Pour revenir en grand, rouvre l'appli. Fermer la petite fenêtre arrête la lecture. Fonctionne à partir de Fire OS 6.
- **Lecture/Pause, avance et retour rapides** : contrôlent la vidéo ou le son (± 10 s), même dans la petite fenêtre.
- L'écran reste allumé pendant une vidéo. Sans image dans l'image, le son se coupe quand tu reviens à l'accueil de la Fire TV.
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
5. Odeon apparaît dans **Vos applis et chaînes**. Pour l'avoir sur l'accueil : sélectionne-la, appuie sur **Menu (≡)**, puis **Déplacer**.

---

## Mettre à jour plus tard

Chaque fois que tu modifies un fichier du dépôt sur GitHub (ou que tu cliques sur **Actions > Construire l'appli Fire TV > Run workflow**), une nouvelle version est construite à la **même adresse**.

**Depuis l'appli (le plus simple)** : Menu (≡) > **Mettre à jour l'appli**. L'appli vérifie GitHub, télécharge la nouvelle version, puis la Fire TV demande de confirmer avec **Installer**. L'appli se ferme pendant l'installation : relance-la ensuite. Au lancement, l'appli te prévient aussi quand une nouvelle version est disponible, et le bouton devient « Installer la mise à jour 1.0.x ».

La toute première fois, la Fire TV demande d'autoriser Odeon à installer des applis :
**Paramètres > Ma Fire TV > Options pour les développeurs > Installer des applis inconnues > Odeon : Activé**.

**Sans passer par l'appli** : refais l'étape 3 avec le même code Downloader.

Dans les deux cas, la mise à jour s'installe par-dessus : ta connexion et tes réglages sont gardés.

> Cette fonction arrive avec la version 1.0.2 : la version 1.0.1 doit être mise à jour une dernière fois avec Downloader.

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
