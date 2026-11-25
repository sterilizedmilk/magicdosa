package magicdosa.agent.encoder;

import com.github.luben.zstd.Zstd;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.compression.AbstractCompressionCodec;
import org.apache.arrow.vector.compression.CompressionCodec;
import org.apache.arrow.vector.compression.CompressionUtil;

/**
 * Arrow IPC buffer compression with zstd (2026-11-25: shards are compressed, the disk is the bottleneck). Arrow's own
 * codec lives in the arrow-compression module; this is the same format (each buffer: its uncompressed length, then the
 * zstd frame) over zstd-jni, which readers such as pyarrow decode as Arrow's ZSTD.
 */
public final class ZstdCodec extends AbstractCompressionCodec {
    private final int level;

    public ZstdCodec(int level) {
        this.level = level;
    }

    @Override
    protected ArrowBuf doCompress(BufferAllocator allocator, ArrowBuf uncompressed) {
        long maxSize = Zstd.compressBound(uncompressed.writerIndex());
        long dstSize = CompressionUtil.SIZE_OF_UNCOMPRESSED_LENGTH + maxSize;
        ArrowBuf out = allocator.buffer(dstSize);
        long written = Zstd.compressUnsafe(out.memoryAddress() + CompressionUtil.SIZE_OF_UNCOMPRESSED_LENGTH, maxSize,
            uncompressed.memoryAddress(), uncompressed.writerIndex(), level);
        if (Zstd.isError(written)) {
            out.close();
            throw new IllegalStateException("zstd compression failed: " + Zstd.getErrorName(written));
        }
        out.writerIndex(CompressionUtil.SIZE_OF_UNCOMPRESSED_LENGTH + written);
        return out;
    }

    @Override
    protected ArrowBuf doDecompress(BufferAllocator allocator, ArrowBuf compressed) {
        long length = readUncompressedLength(compressed);
        ArrowBuf out = allocator.buffer(length);
        long read = Zstd.decompressUnsafe(out.memoryAddress(), length,
            compressed.memoryAddress() + CompressionUtil.SIZE_OF_UNCOMPRESSED_LENGTH,
            compressed.writerIndex() - CompressionUtil.SIZE_OF_UNCOMPRESSED_LENGTH);
        if (Zstd.isError(read)) {
            out.close();
            throw new IllegalStateException("zstd decompression failed: " + Zstd.getErrorName(read));
        }
        out.writerIndex(length);
        return out;
    }

    @Override
    public CompressionUtil.CodecType getCodecType() {
        return CompressionUtil.CodecType.ZSTD;
    }

    /** Codecs for writers and readers: zstd at {@code level}, otherwise none. */
    public static CompressionCodec.Factory factory(int level) {
        return new CompressionCodec.Factory() {
            @Override
            public CompressionCodec createCodec(CompressionUtil.CodecType type) {
                return createCodec(type, level);
            }

            @Override
            public CompressionCodec createCodec(CompressionUtil.CodecType type, int compressionLevel) {
                return type == CompressionUtil.CodecType.ZSTD ? new ZstdCodec(compressionLevel)
                    : org.apache.arrow.vector.compression.NoCompressionCodec.Factory.INSTANCE.createCodec(type);
            }
        };
    }
}
