package magicdosa.runner.forge.harness;

import magicdosa.protocol.v1.*;
import java.io.*; import java.nio.file.*; import java.util.*; import java.util.zip.*;
/**
 * Measurement (planning item 18): compares the logs of two runs of the same seeds game by game (every record, game IDs
 * aside): identical, or the first record that differs, with both winners.
 *
 * <pre>SameRuns &lt;log folder A&gt; &lt;log folder B&gt;</pre>
 */
public final class SameRuns {
    private SameRuns() {}

  static List<String> read(Path f) throws IOException {
    List<String> out = new ArrayList<>();
    try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
      LogRecord r;
      while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
        out.add(com.google.protobuf.TextFormat.shortDebugString(r).replaceAll("game_id: \"[^\"]*\" ?", ""));
      }
    }
    return out;
  }
  static Map<String, Path> index(Path dir) throws IOException {
    Map<String, Path> m = new TreeMap<>();
    try (var w = Files.walk(dir)) { w.filter(p -> p.toString().endsWith(".pb.gz")).forEach(p -> m.put(p.getFileName().toString(), p)); }
    return m;
  }
  public static void main(String[] a) throws Exception {
    Map<String, Path> x = index(Path.of(a[0])), y = index(Path.of(a[1]));
    int same = 0, diff = 0, missing = 0, sameEnd = 0;
    for (var e : x.entrySet()) {
      Path q = y.get(e.getKey());
      if (q == null) { missing++; continue; }
      List<String> p1 = read(e.getValue()), p2 = read(q);
      if (p1.equals(p2)) { same++; sameEnd++; continue; }
      diff++;
      int i = 0;
      while (i < Math.min(p1.size(), p2.size()) && p1.get(i).equals(p2.get(i))) i++;
      String end1 = p1.isEmpty() ? "" : p1.get(p1.size() - 1), end2 = p2.isEmpty() ? "" : p2.get(p2.size() - 1);
      String w1 = end1.replaceAll(".*winner_seat: ([0-9]).*", "$1"), w2 = end2.replaceAll(".*winner_seat: ([0-9]).*", "$1");
      if (w1.equals(w2)) sameEnd++;
      System.out.println("DIFF " + e.getKey() + " at record " + i + " of " + p1.size() + "/" + p2.size() + " winner " + w1.substring(0, Math.min(1, w1.length())) + "/" + w2.substring(0, Math.min(1, w2.length())));
      System.out.println("   A " + (i < p1.size() ? p1.get(i) : "-").substring(0, Math.min(300, i < p1.size() ? p1.get(i).length() : 1)));
      System.out.println("   B " + (i < p2.size() ? p2.get(i) : "-").substring(0, Math.min(300, i < p2.size() ? p2.get(i).length() : 1)));
    }
    System.out.printf("games %d: identical %d, different %d, missing in B %d; same winner %d%n", x.size(), same, diff, missing, sameEnd);
  }
}
