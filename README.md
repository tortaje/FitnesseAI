# FitNesse AI Verify Plugin

Plugin FitNesse qui ajoute un bouton **Verify with AI** à la page d'édition wiki :
le contenu du textarea est envoyé à un LLM compatible OpenAI, les corrections
proposées sont affichées en diff sélectionnable par hunk, et Appliquer met à jour
le textarea (sauvegarde manuelle habituelle ensuite).

## Prérequis

- FitNesse standalone (testé avec 20251025, devrait fonctionner avec toute version récente ≥ 2023)
- Java 21+

## Installation

1. Build :

   ```bash
   mvn package
   ```

   Le JAR produit est `target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar`.

2. Copier le JAR dans le répertoire `plugins/` de votre instance FitNesse standalone :

   ```bash
   cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar <dossier-fitnesse>/plugins/
   ```

3. Configurer `plugins.properties` à la **racine** de FitNesse (à côté de `FitNesseRoot`, *pas* dans `plugins/`) :

   ```properties
   llm.base.url=http://localhost:11434/v1
   llm.api.key=
   llm.model=llama3.1
   ```

   Propriétés :

   | Propriété            | Obligatoire | Défaut | Description                                              |
   |----------------------|-------------|--------|----------------------------------------------------------|
   | `llm.base.url`       | oui         | —      | URL de base de l'API compatible OpenAI (`.../v1`)        |
   | `llm.model`          | oui         | —      | Nom du modèle                                            |
   | `llm.api.key`        | non         | vide   | Bearer token (vide pour les endpoints locaux)            |
   | `llm.max.tokens`     | non         | 4096   | `max_tokens` de la requête                                |
   | `llm.timeout.seconds`| non         | 120    | Timeout HTTP                                             |
   | `llm.prompt`         | non         | embarqué | Texte inline remplaçant le prompt système par défaut   |

4. Redémarrer FitNesse.

Aucune propriété de registration n'est nécessaire : le plugin se découvre via
ServiceLoader (META-INF/services dans le JAR).

## Utilisation

1. Ouvrir une page existante en édition (`?edit`).
2. Cliquer **Verify with AI** (à côté de Save). Le contenu courant du textarea est
   analysé, modifications non sauvegardées comprises.
3. Dans le modal : gauche = original, droite = proposition ; checkbox par hunk
   (cochés par défaut) ; commentaires du LLM sous le hunk concerné.
4. **Apply (n)** écrit le contenu fusionné dans le textarea — la sauvegarde reste
   manuelle (bouton Save).
5. Si le textarea a changé entre la vérification et Apply, l'application est refusée
   (relancer la vérification).

## Limitations

- Bouton disponible uniquement sur les pages existantes en mode édition (`?edit`),
  pas sur la création de page (`?new`).
- Le LLM doit respecter le format JSON attendu ; en cas de réponse non conforme,
  le message d'erreur inclut le texte brut (repliable dans le modal).

## Checklist de validation manuelle (JS)

Pas de test automatisé JS dans ce projet. Après `mvn package` + installation :

- [ ] Ouvrir `http://localhost:<port>/<Page>?edit` : le bouton « Verify with AI »
      apparaît dans `#save_buttons` ; les onglets Réseau montrent un GET 200 sur
      `.../files/fitnesse/ai-verify/ai-verify.js` et `...ai-verify.css`.
- [ ] Sur une page non existante ou `?new` : pas de bouton.
- [ ] Sans config LLM (`llm.*` absentes) : clic → message listant `llm.base.url`,
      `llm.model`.
- [ ] Avec un endpoint LLM valide : clic → « Vérification… » puis modal avec hunks ;
      décocher un hunk met Apply à jour le compteur ; Apply met à jour le textarea
      (et l'éditeur WYSIWYG si actif) sans sauvegarder.
- [ ] Aucune différence → « Aucune correction proposée. »
- [ ] LLM en erreur (mauvaise URL) → bandeau d'erreur + bouton Réessayer.
- [ ] Modifier le textarea pendant que le modal est ouvert, puis Apply → refus
      avec message explicite.
- [ ] Échap / clic hors modal / ✕ ferment le modal ; le focus revient au bouton.
- [ ] Écran étroit (< 800px) : les colonnes du diff s'empilent.
- [ ] Mode WYSIWYG actif : après Apply, basculer en mode texte montre le contenu
      appliqué (pas d'écrasement par l'ancien contenu iframe).
