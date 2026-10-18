package com.fitnesseai.plugin;

import difflib.Chunk;
import difflib.Delta;
import difflib.DiffUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class DiffService {
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

    List<Hunk> hunks = new ArrayList<>();
    int added = 0;
    int removed = 0;
    int id = 0;
    for (Delta<String> delta : deltas) {
      Chunk<String> source = delta.getOriginal();
      Chunk<String> target = delta.getRevised();
      List<String> hunkOriginal = List.copyOf(source.getLines());
      List<String> hunkCorrected = List.copyOf(target.getLines());
      int startLine = source.getPosition() + 1;
      String type = switch (delta.getType()) {
        case INSERT -> TYPE_ADDED;
        case DELETE -> TYPE_REMOVED;
        default -> TYPE_MODIFIED;
      };
      added += hunkCorrected.size();
      removed += hunkOriginal.size();
      hunks.add(new Hunk(id++, type, startLine, hunkOriginal, hunkCorrected,
        findComment(comments, startLine, hunkCorrected.size())));
    }
    return new Result(hunks, new Stats(added, removed));
  }

  private static String findComment(List<LlmClient.Comment> comments, int startLine, int correctedCount) {
    if (comments == null || correctedCount == 0) {
      return null;
    }
    List<String> matches = new ArrayList<>();
    for (LlmClient.Comment comment : comments) {
      if (comment.line() >= startLine && comment.line() < startLine + correctedCount) {
        matches.add(comment.explanation());
      }
    }
    return matches.isEmpty() ? null : String.join("\n", matches);
  }
}
