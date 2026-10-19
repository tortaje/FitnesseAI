package com.fitnesseai.plugin;

import difflib.Chunk;
import difflib.Delta;
import difflib.DiffUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DiffService {

  private static final int COMMENT_TOLERANCE = 5;
  public static final String TYPE_ADDED = "added";
  public static final String TYPE_REMOVED = "removed";
  public static final String TYPE_MODIFIED = "modified";

  public record Hunk(int id, String type, int startLine, List<String> originalLines,
                     List<String> correctedLines, String comment) {}
  public record Stats(int added, int removed) {}
  public record Result(List<Hunk> hunks, Stats stats) {}

  public Result compute(String original, String corrected, List<LlmClient.Comment> comments) {
    List<String> originalLines = List.of(original.split("\n", -1));
    List<String> correctedLines = List.of(corrected.split("\n", -1));

    List<Delta<String>> deltas = new ArrayList<>(DiffUtils.diff(originalLines, correctedLines).getDeltas());
    deltas.sort(Comparator.comparingInt(d -> d.getOriginal().getPosition()));

    // First pass: collect hunk geometry without comments.
    record Geometry(int id, String type, int startLine, int correctedStart, int correctedCount,
                    List<String> originalLines, List<String> correctedLines) {}
    List<Geometry> geometries = new ArrayList<>();
    int added = 0;
    int removed = 0;
    int id = 0;
    for (Delta<String> delta : deltas) {
      Chunk<String> source = delta.getOriginal();
      Chunk<String> target = delta.getRevised();
      List<String> hunkOriginal = List.copyOf(source.getLines());
      List<String> hunkCorrected = List.copyOf(target.getLines());
      int startLine = source.getPosition() + 1;
      int correctedStart = target.getPosition() + 1;
      String type = switch (delta.getType()) {
        case INSERT -> TYPE_ADDED;
        case DELETE -> TYPE_REMOVED;
        default -> TYPE_MODIFIED;
      };
      added += hunkCorrected.size();
      removed += hunkOriginal.size();
      geometries.add(new Geometry(id++, type, startLine, correctedStart, hunkCorrected.size(),
        hunkOriginal, hunkCorrected));
    }

    // Second pass: assign each comment to at most one hunk.
    List<List<String>> hunkComments = new ArrayList<>();
    for (int i = 0; i < geometries.size(); i++) {
      hunkComments.add(new ArrayList<>());
    }
    if (comments != null) {
      List<LlmClient.Comment> remaining = new ArrayList<>(comments);
      // First: exact range matches (corrected space, then original space for deletions).
      for (LlmClient.Comment comment : new ArrayList<>(remaining)) {
        if (comment.line() <= 0) {
          remaining.remove(comment);
          continue;
        }
        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < geometries.size(); i++) {
          Geometry g = geometries.get(i);
          boolean exact = (g.correctedCount() > 0
            && comment.line() >= g.correctedStart()
            && comment.line() < g.correctedStart() + g.correctedCount())
            || (g.correctedCount() == 0
            && comment.line() >= g.startLine()
            && comment.line() < g.startLine() + Math.max(1, g.originalLines().size()));
          if (exact) {
            bestIndex = i;
            bestDistance = 0;
            break;
          }
        }
        if (bestIndex >= 0) {
          hunkComments.get(bestIndex).add(comment.explanation());
          remaining.remove(comment);
        }
      }
      // Fallback: nearest hunk within tolerance for leftover comments.
      for (LlmClient.Comment comment : remaining) {
        if (comment.line() <= 0) {
          continue;
        }
        int bestIndex = -1;
        int bestDistance = COMMENT_TOLERANCE + 1;
        for (int i = 0; i < geometries.size(); i++) {
          Geometry g = geometries.get(i);
          int distance = Math.min(
            Math.abs(comment.line() - g.correctedStart()),
            Math.abs(comment.line() - g.startLine()));
          if (distance < bestDistance) {
            bestDistance = distance;
            bestIndex = i;
          }
        }
        if (bestIndex >= 0 && bestDistance <= COMMENT_TOLERANCE) {
          hunkComments.get(bestIndex).add(comment.explanation());
        }
      }
    }

    List<Hunk> hunks = new ArrayList<>();
    for (Geometry g : geometries) {
      List<String> joined = hunkComments.get(g.id());
      String comment = joined.isEmpty() ? null : String.join("\n", joined);
      hunks.add(new Hunk(g.id(), g.type(), g.startLine(), g.originalLines(), g.correctedLines(), comment));
    }
    return new Result(hunks, new Stats(added, removed));
  }


}
