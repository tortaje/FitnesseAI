# FitNesse AI Verify — Prompt système externe (`llm.prompt.file`) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Permettre de personnaliser le prompt système via un fichier markdown externe (`llm.prompt.file`), relu à chaque vérification (éditer le fichier prend effet sans redémarrer FitNesse ni recompiler).

**Architecture:** `AiVerifyConfig.systemPrompt()` gagne une source de plus avec priorité `llm.prompt.file` > `llm.prompt` inline > prompt embarqué. La lecture fichier a lieu à chaque appel de `systemPrompt()`, déjà exécuté une fois par requête par `AiVerifyResponder`. Un fichier configuré mais illisible lève `IllegalStateException` ; le responder convertit les `RuntimeException` de `chat()` en erreur JSON 502 affichée dans le modal.

**Tech Stack:** Java 21, Maven, JUnit 5 (`@TempDir`), org.json. Spec : `docs/superpowers/specs/2026-10-18-fitnesse-prompt-file-design.md`.

**Règles projet :** TDD strict (rouge → vert), une commit par tâche, aucun commentaire de code ajouté, style existant respecté.

---

### Task 1: `AiVerifyConfig` — propriété `llm.prompt.file`

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java`
- Test: `src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java`

- [ ] **Step 1: Écrire les tests d'échec**

Ajouter à `AiVerifyConfigTest` ces imports :

```java
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
```

Ajouter ces 4 tests (après `inlinePromptOverridesDefault`, ligne 40) :

```java
  @Test
  void promptFileOverridesInlineAndDefault(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ai-verify-prompt.md");
    Files.writeString(file, "# My prompt\nsecond line", StandardCharsets.UTF_8);
    Properties props = props("llm.prompt", "inline prompt", "llm.prompt.file", file.toString());
    assertEquals("# My prompt\nsecond line", new AiVerifyConfig(props).systemPrompt());
  }

  @Test
  void promptFileIsRereadOnEachCall(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("ai-verify-prompt.md");
    Files.writeString(file, "first version", StandardCharsets.UTF_8);
    AiVerifyConfig config = new AiVerifyConfig(props("llm.prompt.file", file.toString()));
    assertEquals("first version", config.systemPrompt());
    Files.writeString(file, "second version", StandardCharsets.UTF_8);
    assertEquals("second version", config.systemPrompt());
  }

  @Test
  void missingPromptFileThrowsListingPropertyAndPath(@TempDir Path dir) {
    String path = dir.resolve("does-not-exist.md").toString();
    Properties props = props("llm.prompt.file", path);
    IllegalStateException e = assertThrows(IllegalStateException.class,
      () -> new AiVerifyConfig(props).systemPrompt());
    assertTrue(e.getMessage().contains("llm.prompt.file"));
    assertTrue(e.getMessage().contains(path));
  }

  @Test
  void blankPromptFileFallsBackToDefault() {
    Properties props = props("llm.prompt.file", "   ");
    assertTrue(new AiVerifyConfig(props).systemPrompt().contains("JSON object"));
  }
```

- [ ] **Step 2: Lancer le test, vérifier l'échec**

Run: `mvn -q test -Dtest=AiVerifyConfigTest`
Expected: FAILS 3 tests pour la bonne raison (fonctionnalité absente) :
- `promptFileOverridesInlineAndDefault` — obtient le prompt embarqué au lieu du contenu fichier
- `promptFileIsRereadOnEachCall` — obtient le prompt embarqué
- `missingPromptFileThrowsListingPropertyAndPath` — `IllegalStateException` non levée

`blankPromptFileFallsBackToDefault` est rouge-indifférent (déjà vrai aujourd'hui) ; les 5 tests existants restent verts.

- [ ] **Step 3: Implémenter dans `AiVerifyConfig.java`**

Ajouter l'import :

```java
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
```

Ajouter la constante après `PROMPT` (ligne 17) :

```java
  public static final String PROMPT_FILE = "llm.prompt.file";
```

Remplacer `systemPrompt()` (lignes 66-72) par :

```java
  public String systemPrompt() {
    String path = raw(PROMPT_FILE);
    if (!isBlank(path)) {
      return promptFromFile(path);
    }
    String inline = raw(PROMPT);
    if (!isBlank(inline)) {
      return inline;
    }
    return defaultPrompt();
  }
```

Ajouter la méthode privée juste après `defaultPrompt()` :

```java
  private static String promptFromFile(String path) {
    try {
      return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      throw new IllegalStateException(PROMPT_FILE + " points to a missing file: " + path);
    } catch (IOException e) {
      throw new IllegalStateException(PROMPT_FILE + " could not read file " + path + ": " + e.getMessage());
    }
  }
```

(`IOException` et `StandardCharsets` sont déjà importés dans ce fichier.)

- [ ] **Step 4: Lancer le test, vérifier le vert**

Run: `mvn -q test -Dtest=AiVerifyConfigTest`
Expected: tous les tests de la classe PASS (5 existants + 4 nouveaux = 9).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyConfig.java src/test/java/com/fitnesseai/plugin/AiVerifyConfigTest.java
git commit -m "feat: external markdown system prompt via llm.prompt.file"
```

---

### Task 2: `AiVerifyResponder` — erreur de prompt en JSON 502

**Files:**
- Modify: `src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java:42-51`
- Test: `src/test/java/com/fitnesseai/plugin/AiVerifyResponderTest.java`

- [ ] **Step 1: Écrire le test d'échec**

Ajouter l'import à `AiVerifyResponderTest` :

```java
import java.nio.file.Path;
```

Ajouter ce test (après `missingConfigReturns400ListingProperties`) :

```java
  @Test
  void unreadablePromptFileReturns502WithPropertyAndPath() throws Exception {
    Properties props = new Properties();
    props.setProperty("llm.base.url", "http://127.0.0.1:1/v1");
    props.setProperty("llm.model", "test-model");
    String missing = Path.of(System.getProperty("java.io.tmpdir"),
      "ai-verify-missing-" + System.nanoTime() + ".md").toString();
    props.setProperty("llm.prompt.file", missing);
    context = FitNesseUtil.makeTestContext(props);

    JSONObject body = dispatch(verifyRequest("content"));

    assertEquals(502, lastStatus);
    assertTrue(body.getString("error").contains("llm.prompt.file"));
    assertTrue(body.getString("error").contains(missing));
  }
```

- [ ] **Step 2: Lancer le test, vérifier l'échec**

Run: `mvn -q test -Dtest=AiVerifyResponderTest#unreadablePromptFileReturns502WithPropertyAndPath`
Expected: ERROR — `IllegalStateException` propagée hors `makeResponse` (échec du dispatch, pas de réponse JSON).

- [ ] **Step 3: Implémenter dans `AiVerifyResponder.java`**

Dans le bloc `try/catch` autour de `chat()` (lignes 43-51), ajouter un second catch après celui de `LlmException` :

```java
    } catch (RuntimeException e) {
      return jsonResponse(502, new JSONObject().put("error", e.getMessage()));
    }
```

- [ ] **Step 4: Lancer le test, vérifier le vert**

Run: `mvn -q test -Dtest=AiVerifyResponderTest`
Expected: tous les tests de la classe PASS (6 existants + 1 nouveau = 7).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/fitnesseai/plugin/AiVerifyResponder.java src/test/java/com/fitnesseai/plugin/AiVerifyResponderTest.java
git commit -m "fix: surface prompt file errors as JSON 502 in the modal"
```

---

### Task 3: Documentation, exemple smoke, build complet et déploiement

**Files:**
- Modify: `README.md`
- Modify: `/tmp/fitnesse-smoke/plugins.properties`

- [ ] **Step 1: README — ligne de propriété**

Dans le tableau des propriétés (ligne 46, après `llm.prompt`), ajouter :

```markdown
    | `llm.prompt.file`    | non         | —      | Chemin d'un fichier UTF-8 (`.md` recommandé) contenant le prompt système ; relu à chaque vérification, priorité sur `llm.prompt` |
```

- [ ] **Step 2: README — exemple et note « sans redémarrage »**

Dans le bloc properties d'exemple (lignes 31-35), ajouter après `llm.model=llama3.1` :

```properties
   # Prompt système en markdown relu à chaque clic Verify (pas de redémarrage)
   llm.prompt.file=/chemin/vers/ai-verify-prompt.md
```

Dans la section `4. Redémarrer FitNesse.` (ligne 48), remplacer par :

```markdown
4. Redémarrer FitNesse (une seule fois : les propriétés `llm.*` sont lues au
   démarrage). Ensuite, modifier le fichier `llm.prompt.file` prend effet au
   prochain clic sur **Verify with AI** — sans redémarrage ni recompil.

   Pour partir du prompt embarqué :

   ```bash
   unzip -p plugins/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar ai-verify-prompt.txt > ai-verify-prompt.md
   ```
```

- [ ] **Step 3: README — corriger les chaînes FR périmées de la checklist**

Dans « Checklist de validation manuelle (JS) » :
- ligne 82 : remplacer `« Vérification… »` par `« Verifying… »`
- ligne 85 : remplacer `« Aucune correction proposée. »` par `« No corrections proposed. »`
- ligne 86 : remplacer `bouton Réessayer` par `bouton Retry`

- [ ] **Step 4: Example commenté dans le smoke**

Ajouter en fin de `/tmp/fitnesse-smoke/plugins.properties` :

```properties
# System prompt override (markdown, re-read on every Verify click — no restart)
# llm.prompt.file=/tmp/fitnesse-smoke/ai-verify-prompt.md
```

- [ ] **Step 5: Build complet avec tests**

Run: `mvn clean verify 2>&1 | grep -E "Tests run: [0-9]+, Fail|BUILD" | tail -2`
Expected: `Tests run: 39, Failures: 0, Errors: 0, Skipped: 0` (34 existants + 5 nouveaux) puis `BUILD SUCCESS`.

- [ ] **Step 6: Déployer sur l'environnement smoke**

```bash
cp target/fitnesse-ai-verify-1.0.0-SNAPSHOT.jar /tmp/fitnesse-smoke/plugins/
pkill -f "fitnesse.jar -p 8091"; sleep 2
cd /tmp/fitnesse-smoke && nohup java -jar fitnesse.jar -p 8091 >> fitnesse-console.log 2>&1 &
sleep 5
curl -s -o /dev/null -w "FitNesse HTTP %{http_code}\n" http://localhost:8091/FrontPage
```

Expected: `FitNesse HTTP 200`.

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: document llm.prompt.file and refresh UI strings in checklist"
```

---

## Notes pour l'exécution

- Les tests d'assurance : après Task 1 et Task 2, relancer `mvn -q test` complet avant de committer si un doute subsiste.
- L'instanciation par requête de `AiVerifyConfig` dans `AiVerifyResponder:34` est déjà en place — rien à changer pour la lecture à chaque clic.
- Ne pas toucher au prompt embarqué (`/ai-verify-prompt.txt`) ni à la propriété inline `llm.prompt`.
