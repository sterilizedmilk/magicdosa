package magicdosa.agent;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads a deck file. Two formats are accepted: Forge's own ({@code [metadata]}, {@code [Main]}, {@code [Sideboard]})
 * and the Arena/MTGO text export ({@code About} / {@code Name ...}, {@code Companion}, {@code Deck}, {@code Sideboard},
 * one {@code "<count> <card name>"} per line). A companion listed under {@code Companion} is also in the sideboard in
 * that export, and Forge picks a companion from the sideboard, so the {@code Companion} section is skipped.
 */
public final class DeckFiles {
    private static final Pattern CARD_LINE = Pattern.compile("^(\\d+)\\s+(.+?)\\s*$");

    private DeckFiles() {}

    public static Deck load(File file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read deck " + file, e);
        }
        boolean forgeFormat = lines.stream().map(String::trim).anyMatch(l -> l.startsWith("["));
        Deck deck = forgeFormat ? DeckSerializer.fromFile(file) : parseText(lines, file);
        if (deck == null || deck.getMain().isEmpty()) {
            throw new IllegalArgumentException("no main deck in " + file);
        }
        return deck;
    }

    private static Deck parseText(List<String> lines, File file) {
        String name = file.getName().replaceFirst("\\.[^.]*$", "");
        DeckSection section = null;
        boolean skip = false;
        Deck deck = null;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("About")) {
                continue;
            }
            if (line.startsWith("Name ")) {
                name = line.substring(5).trim();
                continue;
            }
            switch (line) {
                case "Deck" -> { section = DeckSection.Main; skip = false; continue; }
                case "Sideboard" -> { section = DeckSection.Sideboard; skip = false; continue; }
                case "Companion", "Commander" -> { skip = true; continue; }
                default -> { }
            }
            Matcher m = CARD_LINE.matcher(line);
            if (!m.matches() || section == null) {
                if (skip) continue;
                throw new IllegalArgumentException(file + ": unexpected line '" + line + "'");
            }
            if (skip) {
                continue;
            }
            if (deck == null) {
                deck = new Deck(name);
            }
            deck.getOrCreate(section).add(m.group(2), Integer.parseInt(m.group(1)));
        }
        return deck;
    }
}
