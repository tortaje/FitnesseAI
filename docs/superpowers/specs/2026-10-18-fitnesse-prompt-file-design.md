# Design : prompt système externe en markdown (`llm.prompt.file`)

Date : 2026-10-18
Statut : approuvé oralement, en attente de revue du spec
Branche : `feature/ai-verify`

## Contexte

Le prompt système actuel est embarqué dans le JAR (`/ai-verify-prompt.txt`, constant
`AiVerifyConfig.DEFAULT_PROMPT_RESOURCE`). Une propriété inline `llm.prompt` existe déjà
mais est peu praticable : prompt long sur une seule ligne de `plugins.properties`, et les
properties sont figés au démarrage de FitNesse. L'utilisateur veut fine-tuner le prompt
sans recompiler.

## Exigence

Pouvoir personnaliser le prompt système via la config, au **format markdown**, avec
application **sans recompiler** et de préférence **sans redémarrer FitNesse**.

## Décision

Nouvelle propriété de config : `llm.prompt.file`.

```properties
llm.prompt.file=/Users/stef/Dev/fitnesse-ai-prompt.md
```

### Règles

1. **Priorité** : `llm.prompt.file` > `llm.prompt` (inline) > prompt embarqué défaut.
2. **Lecture** : le fichier est relu **à chaque requête** Verify (UTF-8), à côté de la
   construction `AiVerifyConfig` déjà faite par requête dans `AiVerifyResponder`.
   Éditer le fichier prend effet au prochain clic sur « Verify with AI » — aucun
   redémarrage, aucun recompil.
3. **Extension** : le contenu est lu en texte brut UTF-8 ; toute extension marche.
   `.md` est le format recommandé et celui documenté dans le README.
4. **Priorité inline conservée** : `llm.prompt` reste supporté (compatibilité), non modifié.
5. **Erreurs** :
   - propriété présente mais fichier absent ou illisible → `systemPrompt()` lève
     `IllegalStateException` avec un message contenant la propriété et le chemin ;
     le responder étend son `catch` autour de `chat()` à `RuntimeException` et renvoie
     une erreur JSON 502 → affichée dans le modal (pas de fallback silencieux vers le
     défaut — un chemin configuré mais cassé doit être visible) ;
   - chemin vide/blank → ignoré, comportement identique à propriété absente.
6. **Prompt embarqué** : inchangé, reste le repli quand aucune propriété n'est posée.

### Frontière de composants

- `AiVerifyConfig.systemPrompt()` : seule porte d'entrée du prompt. Ajoute la lecture
  fichier avec priorité, en préservant le contrat « ne renvoie jamais null ».
- `AiVerifyResponder` : élargit le `catch` autour de `chat()` pour couvrir
  `RuntimeException` (échec de lecture du prompt) → JSON 502.
- `LlmClient` : inchangé (reçoit la string du prompt via `config.systemPrompt()`).

## Tests

`AiVerifyConfigTest` :
- prompt lu depuis un fichier temp quand `llm.prompt.file` est posé (contenu exact,
  multi-lignes conservé) ;
- priorité fichier > inline > défaut (combinaisons) ;
- fichier configuré mais inexistant → exception avec message contenant propriété + chemin ;
- `llm.prompt.file` blank → défaut utilisé ;
- inline `llm.prompt` seul → inchangé (régression).

`AiVerifyResponderTest` :
- config avec `llm.prompt.file` inexistant → 502 JSON dont l'`error` contient la propriété
  et le chemin (le modal affiche l'erreur, pas une page 500 HTML).

## Documentation

- README : nouvelle propriété `llm.prompt.file` avec exemple `.md`, note « no restart
  needed, edit the file and Verify again ».
- `plugins.properties` de l'environnement smoke (`/tmp/fitnesse-smoke`) : exemple commenté.

## Hors périmètre

- Rechargement chaud des autres propriétés (`llm.base.url`, `llm.model`…) — nécessite
  toujours un redémarrage FitNesse.
- Watch/inotify du fichier : la lecture par requête suffit.
- Validation du contenu markdown du prompt (c'est du texte libre pour le LLM).
