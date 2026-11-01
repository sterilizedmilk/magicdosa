package magicdosa.adapter.forge.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Builds {@code oracle-ids.tsv}: Forge card name / token script -> Scryfall oracle_id (design: protocol 카드 식별자; Q3).
 *
 * Cards come from Scryfall's "Oracle Cards" bulk file. Tokens: Forge's edition files list token scripts with collector
 * numbers in their [tokens] section; the token print is looked up on the Scryfall API (set "t" + Scryfall code).
 * Only the cards of the given deck files (and the tokens they create) are written. Run by hand when decks change:
 *
 * <pre>java ... OracleTableBuilder oracle-cards.jsonl.gz decksDir forgeResDir out.tsv</pre>
 */
public final class OracleTableBuilder {
    private static final Pattern TOKEN_SCRIPT = Pattern.compile("TokenScript\\$\\s*([A-Za-z0-9_,]+)");
    private static final Pattern DECK_LINE = Pattern.compile("^(\\d+)\\s+(.+?)(\\|.*)?$");

    private OracleTableBuilder() {}

    public static void main(String[] args) throws Exception {
        Path bulk = Path.of(args[0]);
        Path decks = Path.of(args[1]);
        Path res = Path.of(args[2]);
        Path out = Path.of(args[3]);

        TreeSet<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.list(decks)) {
            for (Path deck : files.filter(p -> p.toString().endsWith(".dck")).toList()) {
                boolean inAbout = false;
                for (String line : Files.readAllLines(deck, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("[") || t.equals("About") || t.equals("Deck") || t.equals("Sideboard") || t.equals("Companion")) {
                        inAbout = t.equals("About") || t.equalsIgnoreCase("[metadata]");
                        continue;
                    }
                    Matcher m = DECK_LINE.matcher(t);
                    if (!inAbout && m.matches()) {
                        names.add(m.group(2).trim());
                    }
                }
            }
        }

        Map<String, Path> scripts = indexCardScripts(res.resolve("cardsfolder"));
        Map<String, JsonObject> scryfall = loadBulk(bulk);

        TreeMap<String, String> rows = new TreeMap<>();
        TreeSet<String> tokenScripts = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        for (String name : names) {
            JsonObject card = scryfall.get(name.toLowerCase(Locale.ROOT));
            if (card == null) {
                missing.add("card " + name);
                continue;
            }
            List<String> faces = new ArrayList<>();
            if (card.has("card_faces")) {
                for (JsonElement f : card.getAsJsonArray("card_faces")) {
                    faces.add(f.getAsJsonObject().get("name").getAsString());
                }
            } else {
                faces.add(card.get("name").getAsString());
            }
            rows.put("card\t" + name, card.get("oracle_id").getAsString() + "\t" + String.join("|", faces));

            Path script = scripts.get(firstFace(name).toLowerCase(Locale.ROOT));
            if (script == null) {
                missing.add("forge script " + name);
                continue;
            }
            Matcher m = TOKEN_SCRIPT.matcher(Files.readString(script, StandardCharsets.UTF_8));
            while (m.find()) {
                for (String s : m.group(1).split(",")) {
                    tokenScripts.add(s.trim());
                }
            }
        }

        Map<String, List<String[]>> tokenPrints = indexTokenPrints(res.resolve("editions"));
        HttpClient http = HttpClient.newHttpClient();
        for (String script : tokenScripts) {
            String found = null;
            for (String[] print : tokenPrints.getOrDefault(script, List.of())) {
                JsonObject token = fetchToken(http, print[0], print[1]);
                if (token != null && token.has("oracle_id")) {
                    found = token.get("oracle_id").getAsString() + "\t" + token.get("name").getAsString();
                    break;
                }
            }
            if (found == null) {
                missing.add("token " + script);
            } else {
                rows.put("token\t" + script, found);
            }
        }

        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
            w.println("# kind\tforge name or token script\toracle_id\tScryfall face names (cards) or token name");
            w.println("# generated by OracleTableBuilder from Scryfall bulk data; do not edit by hand");
            rows.forEach((k, v) -> w.println(k + "\t" + v));
        }
        System.out.printf("wrote %d rows to %s%n", rows.size(), out);
        if (!missing.isEmpty()) {
            System.out.println("MISSING " + missing);
            System.exit(1);
        }
    }

    private static String firstFace(String name) {
        int i = name.indexOf(" // ");
        return i < 0 ? name : name.substring(0, i);
    }

    /** First "Name:" of every Forge card script, lower-case -> file. */
    private static Map<String, Path> indexCardScripts(Path cardsfolder) throws IOException {
        Map<String, Path> out = new HashMap<>();
        try (Stream<Path> files = Files.walk(cardsfolder)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".txt")).toList()) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    if (line.startsWith("Name:")) {
                        out.putIfAbsent(line.substring(5).trim().toLowerCase(Locale.ROOT), p);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** Scryfall non-token cards by full name and by front-face name, lower-case. */
    private static Map<String, JsonObject> loadBulk(Path bulk) throws IOException {
        Map<String, JsonObject> out = new HashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(bulk)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.equals("[") || line.equals("]")) {
                    continue;
                }
                if (line.endsWith(",")) {
                    line = line.substring(0, line.length() - 1);
                }
                JsonObject card = JsonParser.parseString(line).getAsJsonObject();
                String layout = card.get("layout").getAsString();
                if (layout.equals("token") || layout.equals("double_faced_token") || layout.equals("art_series")
                        || layout.equals("emblem") || !card.has("oracle_id")) {
                    continue;
                }
                out.putIfAbsent(card.get("name").getAsString().toLowerCase(Locale.ROOT), card);
                if (card.has("card_faces")) {
                    JsonArray faces = card.getAsJsonArray("card_faces");
                    out.putIfAbsent(faces.get(0).getAsJsonObject().get("name").getAsString().toLowerCase(Locale.ROOT), card);
                }
            }
        }
        return out;
    }

    /** Token script -> [(Scryfall set code, collector number)] from the editions' [tokens] sections. */
    private static Map<String, List<String[]>> indexTokenPrints(Path editions) throws IOException {
        Map<String, List<String[]>> out = new HashMap<>();
        try (Stream<Path> files = Files.list(editions)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".txt")).sorted().toList()) {
                String code = null;
                String scryfallCode = null;
                boolean inTokens = false;
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("[")) {
                        inTokens = t.equalsIgnoreCase("[tokens]");
                        continue;
                    }
                    if (t.startsWith("Code=")) {
                        code = t.substring(5);
                    } else if (t.startsWith("ScryfallCode=")) {
                        scryfallCode = t.substring(13);
                    } else if (inTokens && !t.isEmpty()) {
                        String[] parts = t.split("\\s+");
                        if (parts.length >= 2 && Character.isDigit(parts[0].charAt(0))) {
                            String set = scryfallCode != null ? scryfallCode : code;
                            out.computeIfAbsent(parts[1], k -> new ArrayList<>())
                                .add(new String[] {"t" + set.toLowerCase(Locale.ROOT), parts[0]});
                        }
                    }
                }
            }
        }
        return out;
    }

    private static JsonObject fetchToken(HttpClient http, String set, String number) throws Exception {
        Thread.sleep(120); // Scryfall asks for at most ~10 requests per second
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.scryfall.com/cards/" + set + "/" + number))
            .header("User-Agent", "magicdosa/0.1").header("Accept", "application/json").build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.statusCode() == 200 ? JsonParser.parseString(resp.body()).getAsJsonObject() : null;
    }
}
