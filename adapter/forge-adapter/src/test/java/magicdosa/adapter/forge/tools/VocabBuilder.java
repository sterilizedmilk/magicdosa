package magicdosa.adapter.forge.tools;

import com.google.protobuf.Descriptors;
import com.google.protobuf.Message;
import forge.GuiDesktop;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.agent.DeckFiles;
import magicdosa.agent.encoder.Vocab;
import magicdosa.protocol.v1.AbilityKey;
import magicdosa.protocol.v1.LogRecord;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Builds or extends agent-core's vocabulary file (encoder E1): every card and token of the oracle table, every printed
 * ability paragraph of the decks' cards, and every ability key, keyword name, counter kind, designation and type that
 * appears in the given game logs. Existing entries keep their place (append-only); new ones are appended, sorted.
 *
 * <pre>VocabBuilder &lt;decks dir&gt; &lt;existing vocab.tsv or -&gt; &lt;out vocab.tsv&gt; &lt;version&gt; &lt;log dir&gt;...</pre>
 * (working directory forge/forge-gui)
 */
public final class VocabBuilder {
    private VocabBuilder() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Path deckDir = Path.of(args[0]);
        Path existing = args[1].equals("-") ? null : Path.of(args[1]);
        Path out = Path.of(args[2]);
        int version = Integer.parseInt(args[3]);
        CardKeys keys = CardKeys.load();

        Map<String, Set<String>> found = new TreeMap<>();
        for (String k : List.of("card", "ability", "keyword", "string")) {
            found.put(k, new TreeSet<>());
        }
        // decks: cards and their printed ability paragraphs (Q1: (oracle_id, face, index), non-keyword paragraphs)
        try (Stream<Path> decks = Files.list(deckDir)) {
            for (Path d : decks.filter(p -> p.toString().endsWith(".dck")).toList()) {
                Deck deck = DeckFiles.load(d.toFile());
                List<PaperCard> cards = new ArrayList<>(deck.getMain().toFlatList());
                if (deck.has(DeckSection.Sideboard)) {
                    cards.addAll(deck.get(DeckSection.Sideboard).toFlatList());
                }
                for (PaperCard pc : cards) {
                    String oracle = keys.oracleIdByName(pc.getName());
                    found.get("card").add(oracle);
                    CardRules rules = pc.getRules();
                    List<ICardFace> faces = new ArrayList<>();
                    rules.getAllFaces().forEach(faces::add);
                    for (int f = 0; f < faces.size(); f++) {
                        List<String> paragraphs = CardKeys.abilityParagraphs(faces.get(f).getOracleText());
                        for (int i = 0; i < paragraphs.size(); i++) {
                            found.get("ability").add("p:" + oracle + ":" + (f + 1) + ":" + (i + 1));
                        }
                    }
                }
            }
        }
        // every card and token of the oracle table
        List<String> table;
        try (InputStream t = CardKeys.class.getResourceAsStream("oracle-ids.tsv")) {
            table = new String(t.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        for (String line : table) {
            String[] f = line.split("\t");
            if (f.length >= 3 && (f[0].equals("card") || f[0].equals("token"))) {
                found.get("card").add(f[2]);
            }
        }
        // logs
        for (int i = 4; i < args.length; i++) {
            try (Stream<Path> files = Files.walk(Path.of(args[i]))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".pb.gz")).toList()) {
                    try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                        LogRecord r;
                        while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                            walk(r, found);
                        }
                    }
                }
            }
        }
        // keep the existing order, append the new entries
        List<String> lines = new ArrayList<>();
        Set<String> have = new LinkedHashSet<>();
        if (existing != null) {
            for (String line : Files.readAllLines(existing, StandardCharsets.UTF_8)) {
                if (!line.startsWith("#") && !line.isBlank()) {
                    have.add(line);
                    lines.add(line);
                }
            }
        }
        int added = 0;
        for (Map.Entry<String, Set<String>> e : found.entrySet()) {
            for (String v : e.getValue()) {
                String line = e.getKey() + "\t" + v;
                if (!v.isEmpty() && have.add(line)) {
                    lines.add(line);
                    added++;
                }
            }
        }
        List<String> file = new ArrayList<>();
        file.add("#version " + version);
        file.add("# agent-core vocabulary (encoder E1): kind<TAB>value, indices in file order per kind from 3");
        file.add("# (0 padding, 1 hidden card, 2 not in the vocabulary). Append only. Built by tools.VocabBuilder.");
        file.addAll(lines);
        Files.write(out, file, StandardCharsets.UTF_8);
        System.out.println("vocabulary: " + lines.size() + " entries, " + added + " new, written to " + out);
    }

    /** Collects vocabulary values from any message: oracle_ids, ability keys and the strings the encoder looks up. */
    private static void walk(Message m, Map<String, Set<String>> found) {
        if (m instanceof AbilityKey k) {
            if (k.getKeyCase() != AbilityKey.KeyCase.KEY_NOT_SET) {
                found.get("ability").add(Vocab.abilityName(k));
                if (k.hasKeyword()) {
                    found.get("keyword").add(k.getKeyword().getName());
                }
            }
            return;
        }
        String type = m.getDescriptorForType().getName();
        for (Map.Entry<Descriptors.FieldDescriptor, Object> e : m.getAllFields().entrySet()) {
            Descriptors.FieldDescriptor fd = e.getKey();
            List<?> values = fd.isRepeated() ? (List<?>) e.getValue() : List.of(e.getValue());
            for (Object v : values) {
                if (v instanceof Message child) {
                    walk(child, found);
                } else if (v instanceof String s && !s.isEmpty()) {
                    String name = fd.getName();
                    if (name.endsWith("oracle_id")) {
                        found.get("card").add(s);
                    } else if (type.equals("Counter") && name.equals("kind")) {
                        found.get("string").add("counter:" + s);
                    } else if (type.equals("Designation") && name.equals("name")) {
                        found.get("string").add("designation:" + s);
                    } else if (name.equals("named_type")) {
                        found.get("string").add("type:" + s);
                    }
                }
            }
        }
    }
}
