package com.fitnesseai.plugin;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import static com.fitnesseai.plugin.DiffService.TYPE_ADDED;
import static com.fitnesseai.plugin.DiffService.TYPE_MODIFIED;
import static com.fitnesseai.plugin.DiffService.TYPE_REMOVED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffServiceTest {

  private final DiffService service = new DiffService();

  @Test
  void noChangesProducesNoHunks() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nb\nc", List.of());
    assertTrue(result.hunks().isEmpty());
    assertEquals(0, result.stats().added());
    assertEquals(0, result.stats().removed());
  }

  @Test
  void singleLineChangeIsModifiedHunk() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nB\nc", List.of());

    assertEquals(1, result.hunks().size());
    DiffService.Hunk hunk = result.hunks().get(0);
    assertEquals(0, hunk.id());
    assertEquals(TYPE_MODIFIED, hunk.type());
    assertEquals(2, hunk.startLine());
    assertEquals(List.of("b"), hunk.originalLines());
    assertEquals(List.of("B"), hunk.correctedLines());
    assertEquals(1, result.stats().added());
    assertEquals(1, result.stats().removed());
  }

  @Test
  void insertionIsAddedHunk() {
    DiffService.Result result = service.compute("a\nc", "a\nb\nc", List.of());

    DiffService.Hunk hunk = result.hunks().get(0);
    assertEquals(TYPE_ADDED, hunk.type());
    assertEquals(2, hunk.startLine());
    assertEquals(List.of(), hunk.originalLines());
    assertEquals(List.of("b"), hunk.correctedLines());
  }

  @Test
  void deletionIsRemovedHunk() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nc", List.of());

    DiffService.Hunk hunk = result.hunks().get(0);
    assertEquals(TYPE_REMOVED, hunk.type());
    assertEquals(2, hunk.startLine());
    assertEquals(List.of("b"), hunk.originalLines());
    assertEquals(List.of(), hunk.correctedLines());
  }

  @Test
  void commentInsideCorrectedRangeIsAttached() {
    DiffService.Result result = service.compute("a\nb\nc", "a\nB\nc",
      List.of(new LlmClient.Comment(2, "typo ici")));

    assertEquals("typo ici", result.hunks().get(0).comment());
  }

  @Test
  void commentOutsideAnyHunkIsIgnored() {
    DiffService.Result result = service.compute("a\\nb\\nc", "a\\nB\\nc",
      List.of(new LlmClient.Comment(100, "hors hunk")));

    assertNull(result.hunks().get(0).comment());
  }

  @Test
  void trailingNewlineIsPreservedThroughMerge() {
    String original = "a\n";
    String corrected = "a\nb\n";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(corrected, merge(original, result.hunks(), Set.of(0)));
  }

  @Test
  void mergeWithAllHunksCheckedReconstructsCorrected() {
    String original = "one\ntwo\nthree\nfour\nfive";
    String corrected = "one\nTWO\nthree\nFOUR\nfive";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(corrected, merge(original, result.hunks(), Set.of(0, 1)));
  }

  @Test
  void mergeWithNoHunksCheckedKeepsOriginal() {
    String original = "one\ntwo\nthree";
    String corrected = "one\nTWO\nthree";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(original, merge(original, result.hunks(), Set.of()));
  }

  @Test
  void mergeWithPartialSelectionAppliesOnlyCheckedHunks() {
    String original = "a\nb\nc\nd";
    String corrected = "A\nb\nC\nd";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals("A\nb\nc\nd", merge(original, result.hunks(), Set.of(0)));
    assertEquals("a\nb\nC\nd", merge(original, result.hunks(), Set.of(1)));
  }

  @Test
  void hunksAreOrderedByPosition() {
    String original = "a\nb\nc\nd\ne";
    String corrected = "A\nb\nC\nd\nE";
    DiffService.Result result = service.compute(original, corrected, List.of());

    assertEquals(3, result.hunks().size());
    assertEquals(1, result.hunks().get(0).startLine());
    assertEquals(3, result.hunks().get(1).startLine());
    assertEquals(5, result.hunks().get(2).startLine());
  }

  @Test
  void debugLogsDiffSummaryAndCommentAssignment() {
    try (LogCapture logs = new LogCapture(DiffService.class)) {
      service.compute("a\nb\nc", "a\nB\nc", List.of(new LlmClient.Comment(2, "cap")), true);
      assertTrue(logs.has(Level.INFO, "diff deltas=1"));
      assertTrue(logs.has(Level.INFO, "comment line=2 assigned to hunk=0"));
    }

    try (LogCapture logs = new LogCapture(DiffService.class)) {
      service.compute("a\nb\nc", "a\nB\nc", List.of(new LlmClient.Comment(100, "loin")));
      assertFalse(logs.has(Level.INFO, "diff deltas="));
      assertFalse(logs.has(Level.INFO, "assigned"));
      assertTrue(logs.has(Level.FINE, "diff deltas=1"));
      assertTrue(logs.has(Level.FINE, "comment line=100 dropped"));
    }
  }

  /** Mirror of the merge algorithm implemented in ai-verify.js — keeps both sides in lockstep. */
  static String merge(String original, List<DiffService.Hunk> hunks, Set<Integer> checked) {
    List<String> lines = List.of(original.split("\n", -1));
    List<String> out = new ArrayList<>();
    int cursor = 0;
    for (DiffService.Hunk hunk : hunks) {
      int start = hunk.startLine() - 1;
      while (cursor < start) {
        out.add(lines.get(cursor));
        cursor++;
      }
      out.addAll(checked.contains(hunk.id()) ? hunk.correctedLines() : hunk.originalLines());
      cursor += hunk.originalLines().size();
    }
    while (cursor < lines.size()) {
      out.add(lines.get(cursor));
      cursor++;
    }
    return String.join("\n", out);
  }
}
