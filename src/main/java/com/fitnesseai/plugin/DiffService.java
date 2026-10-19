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
    if (comments != null && !geometries.isEmpty()) {
      // Sort comments by line number so assignment follows document order.
      List<LlmClient.Comment> sorted = new ArrayList<>(comments);
      sorted.sort(Comparator.comparingInt(LlmClient.Comment::line));

      int hi = 0;
      for (LlmClient.Comment comment : sorted) {
        if (comment.line() <= 0) {
          continue;
        }
        // Advance pointer past hunks that are clearly before this comment.
        while (hi + 1 < geometries.size()
            && geometries.get(hi + 1).correctedStart() <= comment.line()) {
          hi++;
        }
        // Pick the closer of the current and next hunk, if within tolerance.
        int bestIndex = hi;
        int bestDistance = Math.min(
            Math.abs(comment.line() - geometries.get(hi).correctedStart()),
            Math.abs(comment.line() - geometries.get(hi).startLine()));
        if (hi + 1 < geometries.size()) {
          int nextDistance = Math.min(
              Math.abs(comment.line() - geometries.get(hi + 1).correctedStart()),
              Math.abs(comment.line() - geometries.get(hi + 1).startLine()));
          if (nextDistance < bestDistance) {
            bestDistance = nextDistance;
            bestIndex = hi + 1;
          }
        }
        if (bestDistance <= COMMENT_TOLERANCE) {
          hunkComments.get(bestIndex).add(comment.explanation());
          hi = bestIndex;
          // Advance so the next comment prefers subsequent hunks.
          if (hi + 1 < geometries.size()) {
            hi++;
          }
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
