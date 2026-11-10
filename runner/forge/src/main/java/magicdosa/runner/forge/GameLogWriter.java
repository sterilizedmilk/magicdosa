package magicdosa.runner.forge;

import magicdosa.adapter.forge.LogSink;
import magicdosa.protocol.v1.LogRecord;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

/** One game's log: length-delimited {@link LogRecord}s, header first (design: 학습 데이터 경로; Q11). */
public final class GameLogWriter implements Closeable, magicdosa.adapter.forge.LogSink {
    private final OutputStream out;
    private final MessageDigest digest;

    private GameLogWriter(OutputStream out) {
        this.out = out;
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Gzipped log file. */
    public static GameLogWriter open(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        return new GameLogWriter(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(file))));
    }

    /** A writer that drops everything (benchmarks). */
    public static GameLogWriter discard() {
        return new GameLogWriter(OutputStream.nullOutputStream());
    }

    @Override
    public synchronized void write(LogRecord record) {
        try {
            byte[] bytes = record.toByteArray();
            digest.update(bytes);
            record.writeDelimitedTo(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** SHA-256 of every record written so far, hex: equal logs give equal digests (regression runs). */
    public synchronized String digest() {
        try {
            return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest());
        } catch (CloneNotSupportedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
