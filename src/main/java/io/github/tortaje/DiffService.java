package io.github.tortaje;

import difflib.Chunk;
import difflib.Delta;
import difflib.DiffUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DiffService {
  private static final Logger LOG = Logger.getLogger(DiffService.class.getName());

  /** Max line distance between an LLM comment and its hunk before the comment is dropped. */
  private static final int COMMENT_TOLERANCE = 15;
  public static final String TYPE_ADDED = "added";
  public static final String TYPE_REMOVED = "removed";
  public static final String TYPE_MODIFIED = "modified";

  public record Hunk(int id, String type, int startLine, List<String> originalLines,
                     List<String> correctedLines, String comment) {}
  public record Stats(int added, int removed) {}
  public record Result(List<Hunk> hunks, Stats stats) {}

  public Result compute(String original, String corrected, List<LlmClient.Comment> comments) {
    return compute(original, corrected, comments, false);
  }

  public Result compute(String original, String corrected, List<LlmClient.Comment> comments, boolean debug) {
    Level verbose = debug ? Level.INFO : Level.FINE;
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
    LOG.log(verbose, "[ai-verify] diff deltas=" + geometries.size()
      + " added=" + added + " removed=" + removed);

    // Second pass: assign each comment to at most one hunk.
    List<List<String>> hunkComments = new ArrayList<>();
    for (int i = 0; i < geometries.size(); i++) {
      hunkComments.add(new ArrayList<>());
    }
    if (comments != null && !geometries.isEmpty()) {
      // Sort comments by line number to recover document order.
      List<LlmClient.Comment> sorted = new ArrayList<>(comments);
      sorted.sort(Comparator.comparingInt(LlmClient.Comment::line));
      sorted.removeIf(c -> c.line() <= 0);

      int n = geometries.size();
      int m = sorted.size();
      if (m > 0) {
        // Distribute comments across hunks in document order: the k-th comment
        // (0-based) maps to hunk floor(k * n / m). This avoids pile-up when
        // the model's line numbers are inaccurate, while keeping explanations
        // aligned with the right region of the page.
        for (int k = 0; k < m; k++) {
          int hunkIndex = Math.min((k * n) / m, n - 1);
          LlmClient.Comment c = sorted.get(k);
          int distance = Math.min(
              Math.abs(c.line() - geometries.get(hunkIndex).correctedStart()),
              Math.abs(c.line() - geometries.get(hunkIndex).startLine()));
          if (distance <= COMMENT_TOLERANCE) {
            LOG.log(verbose, () -> "[ai-verify] comment line=" + c.line()
              + " assigned to hunk=" + hunkIndex);
            hunkComments.get(hunkIndex).add(c.explanation());
          } else {
            LOG.log(verbose, () -> "[ai-verify] comment line=" + c.line()
              + " dropped (distance=" + distance + ", tolerance=" + (COMMENT_TOLERANCE) + ")");
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
