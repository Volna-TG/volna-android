package app.volna;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class TgWebMsgSplitter {
    private static final int LIMIT = 16 * 1024 * 1024;
    private final boolean abridged;
    private final byte[] header = new byte[4];
    private int headerSize;
    private int expected;
    private ByteArrayOutputStream pending;
    private boolean passthrough;

    TgWebMsgSplitter(int tag) {
        abridged = tag == 0xefefefef;
        passthrough = !abridged && tag != 0xeeeeeeee && tag != 0xdddddddd;
    }

    List<byte[]> split(byte[] encrypted, byte[] plain) {
        if (encrypted.length == 0) return Collections.emptyList();
        if (passthrough) return Collections.singletonList(encrypted);
        List<byte[]> frames = new ArrayList<>();
        int offset = 0;
        while (offset < encrypted.length) {
            int start = offset;
            if (expected == 0) {
                while (offset < plain.length && headerSize < headerLength()) {
                    header[headerSize++] = plain[offset++];
                }
                if (headerSize == headerLength()) {
                    expected = frameLength();
                    if (expected <= headerSize || expected > LIMIT) {
                        passthrough = true;
                        if (pending != null) frames.add(pending.toByteArray());
                        frames.add(slice(encrypted, start, encrypted.length));
                        pending = null;
                        break;
                    }
                }
            }
            int buffered = pending == null ? 0 : pending.size();
            if (expected != 0) offset = start + Math.min(encrypted.length - start, expected - buffered);
            if (expected != 0 && buffered + offset - start == expected) {
                if (pending == null) frames.add(slice(encrypted, start, offset));
                else {
                    pending.write(encrypted, start, offset - start);
                    frames.add(pending.toByteArray());
                    pending = null;
                }
                headerSize = 0;
                expected = 0;
            } else {
                if (pending == null) pending = new ByteArrayOutputStream(Math.max(4, offset - start));
                pending.write(encrypted, start, offset - start);
            }
        }
        return frames;
    }

    private int headerLength() {
        if (!abridged) return 4;
        return headerSize == 0 || (header[0] & 127) != 127 ? 1 : 4;
    }

    private int frameLength() {
        if (abridged) {
            int words = headerSize == 1 ? header[0] & 127 :
                    (header[1] & 255) | ((header[2] & 255) << 8) | ((header[3] & 255) << 16);
            return headerSize + words * 4;
        }
        return 4 + ((header[0] & 255) | ((header[1] & 255) << 8)
                | ((header[2] & 255) << 16) | ((header[3] & 127) << 24));
    }

    private static byte[] slice(byte[] data, int start, int end) {
        return start == 0 && end == data.length ? data : Arrays.copyOfRange(data, start, end);
    }

    List<byte[]> flush() {
        if (pending == null) return Collections.emptyList();
        byte[] tail = pending.toByteArray();
        pending = null;
        expected = headerSize = 0;
        return Collections.singletonList(tail);
    }
}
