# FitNesse AI Verify Plugin — Design

**Date :** 2026-10-18
**Statut :** Validé

## Objectif

Ajouter à FitNesse un plugin, installable dans le répertoire `plugins/` de la version standalone, qui propose un bouton « Verify with AI » sur la page d'édition wiki. Le bouton envoie le contenu courant du textarea à un LLM configurable (API compatible OpenAI), reçoit une version corrigée, calcule un diff, et affiche un modal permettant de sélectionner et appliquer des corrections par hunk dans le textarea avant sauvegarde normale.

## Contraintes & décisions validées

- Fournisseur LLM : **API compatible OpenAI** (URL de base configurable → OpenAI cloud, Ollama, LM Studio, vLLM, OpenRouter…).
- Configuration LLM dans **`plugins/plugins.properties`** (standard FitNesse, pas d'UI de config).
- Le bouton analyse le **contenu actuel du textarea** (modifications non sauvegardées comprises).
- Sélection des corrections **par hunk de diff**.
- « Apply » met à jour le **textarea** ; l'utilisateur sauvegarde lui-même (pas de sauvegarde automatique).
- Le LLM renvoie le **contenu complet corrigé** (pas un patch) ; le diff est calculé côté plugin.

## Architecture (approche 1 : plugin JS + responder)

JAR Maven unique dans `plugins/`. Aucune modification du core FitNesse.

### Composants Java

- **`AiVerifyPluginFactory`** — implémente `PluginFeatureFactory`, enregistré via `PluginFeatureFactory.Impl` dans `plugins.properties`. Injecte le JS/CSS via les *component factories* FitNesse (pages d'édition uniquement) et enregistre le responder.
- **`AiVerifyResponder`** — `POST /responder/ai-verify`. Lit la config, appelle le LLM, calcule le diff, renvoie du JSON.
- **`LlmClient`** — client HTTP Java 8 compatible (`HttpURLConnection`) vers `POST {base.url}/chat/completions`.
- **`DiffService`** — découpage original/corrigé en hunks ; attache les commentaires LLM par ligne.
- **`AiVerifyConfig`** — lecture/validation des propriétés `llm.*`.

### Composants client (ressources embarquées dans le JAR)

- **`ai-verify.js`** — détection de la page d'édition, injection du bouton, appel du responder, rendu du modal, sélection des hunk, fusion dans le textarea. Inclut une copie minimale de jsdiff.
- **`ai-verify.css`** — styles modal/diff.
- **`ai-verify-prompt.txt`** — prompt système par défaut (syntaxe FitNesse/wikitext), surchargeable.

## Flux de données & contrat d'API

1. Clic sur « Verify with AI » → `POST /responder/ai-verify` avec `{ "pageName": "...", "content": "..." }`.
2. Responder : validation de la config (sinon erreur explicite), appel LLM avec consigne de répondre **uniquement en JSON** :
   ```json
   { "corrected": "contenu corrigé complet",
     "comments": [ { "line": 12, "explanation": "Balise !style-end manquante" } ] }
   ```
3. `DiffService` calcule les hunks entre `content` et `corrected`, attache les commentaires dont la ligne tombe dans le hunk.
4. Réponse du responder :
   ```json
   { "hunks": [ { "id": 0, "type": "modified", "lines": [...], "comment": "..." } ],
     "stats": { "added": 3, "removed": 2 } }
   ```
   Types de hunk : `added`, `removed`, `modified`.
5. JS : modal deux colonnes (original à gauche, corrigé à droite), checkbox par hunk (cochées par défaut), commentaires LLM sous le hunk concerné.
6. « Apply » : reconstruction du contenu n'incluant que les hunk cochés → écriture dans le textarea → fermeture du modal.

### Cas particuliers

- Réponse LLM non-JSON → message d'erreur dans le modal avec le texte brut.
- Aucune différence → message « Aucune correction proposée ».
- Erreur HTTP/timeout → bandeau d'erreur dans le modal + bouton « Réessayer ».
- Le contenu du textarea a changé entre l'appel et « Apply » → refus de l'application (comparaison par hash) plutôt que d'écraser les modifications.

## UX

- **Bouton** : dans la barre d'outils d'édition (à côté de Save/Preview/Cancel), visible uniquement sur `?edit`. Pendant l'appel : « Vérification… », désactivé. Config absente → alerte expliquant les propriétés requises.
- **Modal** : overlay plein écran, panneau centré (~90%), fermable par ✕/Échap. En-tête : titre « Verify with AI — <page> » + stats. Deux colonnes côte à côte avec scroll horizontal synchronisé, contexte gris autour des hunks. Pied : « Apply (n) », « Tout sélectionner / Tout désélectionner », « Annuler ». Responsive (colonnes empilées sur écran étroit). Focus piège + navigation clavier.

## Configuration (`plugins/plugins.properties`)

```properties
PluginFeatureFactory.Impl=com.fitnesseai.plugin.AiVerifyPluginFactory
llm.base.url=http://localhost:11434/v1
llm.api.key=sk-...          # vide accepté pour endpoints locaux
llm.model=llama3.1
llm.max.tokens=4096
llm.timeout.seconds=120
llm.prompt=<texte inline>   # optionnel : remplace le prompt système par défaut (texte inline, pas de fichier)
```

Relecture de la config à chaque requête (pas de cache) → changements sans redémarrage.

## Sécurité

- Clé API jamais envoyée au navigateur ni journalisée.
- Responder : POST uniquement (GET → 405).
- Contenu transmis comme champ JSON ; prompt système fixe (pas d'injection).
- Pas de logique d'auth propre : le responder s'exécute dans le contexte FitNesse et honore sa sécurité existante.

## Gestion des erreurs

- Config manquante → message listant les propriétés à définir.
- Erreur LLM (HTTP >= 400, timeout, JSON invalide) → message dans le modal + détails bruts repliables (jamais la clé).
- Conflit de version textarea → refus avec message explicite.

## Tests

- Unitaires Java : `AiVerifyConfig`, `DiffService` (hunks + commentaires), `LlmClient` (serveur HTTP mocké), parsing réponse LLM.
- Intégration responder avec le framework de test FitNesse : JSON attendu.
- JS : pas de build Node dans le projet → checklist de validation manuelle dans le README.

## Packaging & installation

- Java 8 compatible ; `mvn package` → `fitnesse-ai-verify-<version>-jar-with-dependencies.jar` (JS/CSS/prompt empaquetés dans le JAR).
- Dépendances externes minimales (java-diff-utils ou diff maison).
- Installation : copier le JAR dans `plugins/`, configurer les propriétés `llm.*` dans `plugins/plugins.properties`, redémarrer FitNesse standalone.
