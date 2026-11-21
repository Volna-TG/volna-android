package app.volna;

import java.io.*;

final class ApkPatch {
    private static final int MAGIC = 0x564c5031;
    static final long APK_LIMIT = 128L * 1024 * 1024;
    static final long PATCH_LIMIT = 16L * 1024 * 1024;

    static void apply(File base, File patch, File output, long targetSize) throws IOException {
        if (targetSize <= 0 || targetSize > APK_LIMIT) throw new IOException("Invalid APK size");
        try (RandomAccessFile old = new RandomAccessFile(base, "r");
             DataInputStream delta = new DataInputStream(new BufferedInputStream(new FileInputStream(patch)));
             FileOutputStream file = new FileOutputStream(output);
             BufferedOutputStream out = new BufferedOutputStream(file)) {
            if (delta.readInt() != MAGIC || delta.readLong() != targetSize) throw new IOException("Invalid patch");
            long remaining = targetSize;
            byte[] buffer = new byte[65536];
            while (remaining > 0) {
                int command = delta.readUnsignedByte();
                long offset = command == 0 ? delta.readLong() : 0;
                int length = delta.readInt();
                if (length <= 0 || length > remaining || (command != 0 && command != 1))
                    throw new IOException("Invalid patch command");
                if (command == 0) {
                    if (offset < 0 || offset > old.length() - length) throw new IOException("Invalid copy range");
                    old.seek(offset);
                }
                for (int left = length; left > 0;) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    int count = Math.min(left, buffer.length);
                    if (command == 0) old.readFully(buffer, 0, count);
                    else delta.readFully(buffer, 0, count);
                    out.write(buffer, 0, count);
                    left -= count;
                }
                remaining -= length;
            }
            if (delta.read() != -1) throw new IOException("Trailing patch data");
            out.flush();
            file.getFD().sync();
        }
    }
}
