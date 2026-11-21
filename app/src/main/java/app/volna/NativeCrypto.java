package app.volna;

public final class NativeCrypto {
    static { System.loadLibrary("volna_core"); }
    private NativeCrypto() {}
    public static void initialize() {}
    private static native long create(byte[] key,byte[] iv);
    private static native byte[] apply(long handle,byte[] input);
    private static native void release(long handle);
    private static native byte[] transcodeNative(long decrypt,long encrypt,byte[] input);
    private static native byte[][] transcodePacketNative(long decrypt,long encrypt,byte[] input);
    public static byte[] transcode(Stream decrypt,Stream encrypt,byte[] input){
        synchronized(decrypt){synchronized(encrypt){if(decrypt.handle==0||encrypt.handle==0)throw new IllegalStateException("Соединение закрыто");return transcodeNative(decrypt.handle,encrypt.handle,input);}}
    }
    public static byte[][] transcodePacket(Stream decrypt,Stream encrypt,byte[] input){
        synchronized(decrypt){synchronized(encrypt){if(decrypt.handle==0||encrypt.handle==0)throw new IllegalStateException("Соединение закрыто");return transcodePacketNative(decrypt.handle,encrypt.handle,input);}}
    }
    public static native int[] parseHandshake(byte[] raw,byte[] secret);
    public static final class Stream implements AutoCloseable {
        private long handle;
        Stream(byte[] key,byte[] iv){handle=create(key,iv);if(handle==0)throw new IllegalStateException("Не удалось создать шифрование");}
        public synchronized byte[] update(byte[] bytes){if(handle==0)throw new IllegalStateException("Соединение закрыто");return apply(handle,bytes);}
        public synchronized void close(){if(handle!=0){release(handle);handle=0;}}
    }
}
