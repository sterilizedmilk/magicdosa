package magicdosa.adapter.forge;

import magicdosa.protocol.v1.LogRecord;

/**
 * Where a game's log records go (design: 학습 데이터 경로; Q11). The runner writes them to a file; a shadow game or a
 * search copy drops them.
 */
public interface LogSink {
    /** Drops every record. */
    LogSink DISCARD = record -> { };

    void write(LogRecord record);
}
