package magicdosa.runner.forge.harness;

import magicdosa.protocol.v1.*;
import java.io.*; import java.nio.file.*; import java.util.*; import java.util.zip.*;
/**
 * Measurement (phase-3 planning item 3): priority requests of the logged games whose candidates are only mana abilities
 * and pass, with no potential casts. Reads logs only.
 *
 * <pre>ManaOnlyStops &lt;log folder&gt;</pre>
 */
public final class ManaOnlyStops {
    private ManaOnlyStops() {}

  public static void main(String[] a) throws Exception {
    long games = 0, prio = 0, manaOnly = 0, manaOnlyNoPot = 0, passOnly = 0, decisions = 0;
    Map<String, long[]> byDeck = new TreeMap<>();
    Map<String, Long> sources = new TreeMap<>();
    List<Path> files = new ArrayList<>();
    try (var w = Files.walk(Path.of(a[0]))) { w.filter(p -> p.toString().endsWith(".pb.gz")).forEach(files::add); }
    for (Path f : files) {
      games++;
      String deck = f.getFileName().toString().replaceAll("-vs-.*", "");
      long[] d = byDeck.computeIfAbsent(deck, k -> new long[4]);
      d[0]++;
      try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
        LogRecord r;
        while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
          if (!r.hasDecision() || !r.getDecision().hasRequest()) continue;
          DecisionRequest q = r.getDecision().getRequest();
          decisions++;
          if (q.getType() != DecisionType.DECISION_TYPE_PRIORITY) continue;
          prio++; d[1]++;
          boolean onlyMana = true, anyMana = false;
          for (Candidate c : q.getCandidatesList()) {
            if (c.hasPass()) continue;
            if (c.hasActivateAbility() && c.getActivateAbility().getManaAbility()) { anyMana = true; continue; }
            onlyMana = false;
          }
          if (onlyMana && !anyMana) passOnly++;
          if (onlyMana) {
            manaOnly++;
            if (q.getObservation().getPotentialCastsCount() == 0) {
              manaOnlyNoPot++; d[2]++;
              for (Candidate c : q.getCandidatesList()) if (c.hasActivateAbility()) {
                String k = com.google.protobuf.TextFormat.shortDebugString(c.getActivateAbility().getAbility());
                sources.merge(k, 1L, Long::sum);
              }
            }
          }
        }
      }
    }
    System.out.printf("games=%d decisions=%d priority=%d (%.1f/game)%n", games, decisions, prio, prio / (double) games);
    System.out.printf("pass only=%d (%.2f%%)%n", passOnly, 100.0 * passOnly / prio);
    System.out.printf("mana abilities + pass (incl. pass only)=%d (%.2f%%)%n", manaOnly, 100.0 * manaOnly / prio);
    System.out.printf("  ... and no potential casts=%d (%.2f%% of priority, %.1f per game)%n", manaOnlyNoPot, 100.0 * manaOnlyNoPot / prio, manaOnlyNoPot / (double) games);
    byDeck.forEach((k, d) -> System.out.printf("  seat0 deck %-16s games=%d priority/game=%.1f droppable/game=%.1f (%.1f%%)%n", k, d[0], d[1] / (double) d[0], d[2] / (double) d[0], 100.0 * d[2] / d[1]));
    System.out.println("mana abilities offered at those stops (ability key: stops):");
    sources.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue(), x.getValue())).forEach(e -> System.out.println("  " + e.getValue() + "  " + e.getKey()));
  }
}
