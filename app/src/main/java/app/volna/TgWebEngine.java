package app.volna;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TgWebEngine {
    private static final int HANDSHAKE_LEN = 64;
    private static final int READ_BUFFER = 64 * 1024;
    private static final byte[] ZERO_64 = new byte[64];
    private static final SecureRandom RNG = new SecureRandom();

    private volatile boolean running = false;
    private volatile ServerSocket listener;
    private Thread acceptThread;
    private ExecutorService executor;
    private final Set<Socket> activeClients = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean fatalReported = new AtomicBoolean(false);
    private OnFatalError fatalErrorListener;
    private OnRouteState routeStateListener;
    private byte[] secret = new byte[16];
    private RouteOptions options;
    private RouteDialer dialer;
    private final Set<Socket> remoteSockets = ConcurrentHashMap.newKeySet();
    private volatile boolean routeReady = false;

    public interface OnFatalError {
        void onFatalError(String message);
    }

    public interface OnRouteState {
        void onRouteState(boolean ready, String error);
    }

    public void setFatalErrorListener(OnFatalError listener) {
        this.fatalErrorListener = listener;
    }

    public void setRouteStateListener(OnRouteState listener) {
        this.routeStateListener = listener;
    }

    public synchronized void start(int port, String secretHex, RouteOptions selectedOptions) throws Exception {
        if (running) return;
        NativeCrypto.initialize();
        String normalized = normalizeSecretHex(secretHex);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Неверный ключ подключения");
        secret = hexToBytes(normalized);
        options = selectedOptions;
        routeReady = false;
        fatalReported.set(false);

        listener = ListenerPort.bind(port);
        dialer = new RouteDialer();

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                4, 48, 30L, TimeUnit.SECONDS,
                new java.util.concurrent.SynchronousQueue<>(),
                r -> {
                    Thread th = new Thread(r, "tg-web-client");
                    th.setDaemon(true);
                    return th;
                });
        pool.allowCoreThreadTimeOut(true);
        executor = pool;
        running = true;

        Thread accept = new Thread(this::acceptLoop, "tg-web-accept");
        accept.setDaemon(true);
        acceptThread=accept;
        accept.start();

        try { pool.execute(this::probeRoute); }
        catch (RejectedExecutionException ignored) {}
    }

    public synchronized void stop() {
        running = false;
        routeReady = false;
        try { if (listener != null) listener.close(); } catch (Exception ignored) {}
        listener = null;
        Thread accepting=acceptThread;acceptThread=null;
        if(accepting!=null && accepting!=Thread.currentThread())try{accepting.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        for (Socket s : activeClients) try { s.close(); } catch (Exception ignored) {}
        activeClients.clear();
        if (dialer != null) dialer.close();
        for (Socket s : remoteSockets) closeQuiet(s);
        remoteSockets.clear();
        if (executor != null) executor.shutdownNow();
        executor = null;
    }

    public boolean isRunning() { return running; }
    public int getPort() { ServerSocket s=listener; return s==null ? 0 : s.getLocalPort(); }
    public boolean isRouteReady() { return routeReady; }

    private RawWebSocket openUpstream(int dc, boolean media, int timeoutMs) throws Exception {
        try {
            RawWebSocket ws = dialer.open(dc, timeoutMs);
            if (!running) { ws.abort(); throw new IOException("Остановлено"); }
            markRouteReady();
            return ws;
        } catch (Exception e) {
            markRouteError("Стандартный маршрут недоступен в текущей сети.");
            throw e;
        }
    }

    public void probeRoute() {
        if (options.vpnNetwork.get() != null) return;
        RawWebSocket ws = null;
        try {
            ws = openUpstream(2, false, 10_000);
        } catch (Exception ignored) {
            
        } finally {
            if (ws != null) { ws.abort(); if(dialer!=null) dialer.release(ws); }
        }
    }

    void networkChanged(boolean direct) {
        RouteDialer routes = dialer;
        if (routes != null) routes.networkChanged();
        if (direct || !running) return;
        routeReady = false;
        ExecutorService pool = executor;
        if (pool != null) {
            try { pool.execute(this::probeRoute); } catch (RejectedExecutionException ignored) {}
        }
    }

    private void markRouteReady() {
        routeReady = true;
        OnRouteState l = routeStateListener;
        if (l != null) l.onRouteState(true, "");
    }

    private void markRouteError(String message) {
        routeReady = false;

        OnRouteState l = routeStateListener;
        if (l != null) l.onRouteState(false, message);
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = listener.accept();
                if(!running){closeQuiet(client);break;}
                client.setTcpNoDelay(true);
                client.setKeepAlive(true);
                activeClients.add(client);
                ExecutorService ex = executor;
                if (ex != null) {
                    try { ex.execute(() -> handleClient(client)); }
                    catch (RejectedExecutionException busy) {
                        activeClients.remove(client);
                        closeQuiet(client);
                    }
                } else {
                    activeClients.remove(client);
                    closeQuiet(client);
                }
            } catch (Exception e) {
                if (running) {
                    reportFatal("Локальный MTProto listener остановился: " + safeMessage(e));
                    break;
                }
            }
        }
    }

    private void handleClient(Socket client) {
        RawWebSocket ws = null;
        CryptoContext crypto = null;
        try {
            client.setSoTimeout(10_000);
            byte[] incomingInit = readExactly(client.getInputStream(), HANDSHAKE_LEN);
            Handshake hs = parseHandshake(incomingInit, secret);
            if (hs == null || hs.dc < 1 || (hs.dc > 5 && hs.dc != 203)) {
                return;
            }
            client.setSoTimeout(0);

            android.net.Network vpn = options.vpnNetwork.get();
            if (vpn != null) {
                byte[] init = generateRelayInit(hs.protocolTag, hs.media ? -hs.dc : hs.dc);
                crypto = buildCryptoContext(hs.clientMaterial, secret, init);
                bridgeTcp(client, hs.dc, init, crypto, vpn);
                return;
            }
            try {
                ws = openUpstream(hs.dc, hs.media, 16_000);
            } catch (Exception e) {
                return;
            }

            byte[] relayInit = generateRelayInit(hs.protocolTag, hs.media ? -hs.dc : hs.dc);
            crypto = buildCryptoContext(hs.clientMaterial, secret, relayInit);
            TgWebMsgSplitter splitter = new TgWebMsgSplitter(hs.protocolTagInt);

            ws.send(relayInit);
            bridge(client, ws, crypto, splitter);
        } catch (Exception e) {
        } finally {
            if(crypto!=null)crypto.close();
            activeClients.remove(client);
            closeQuiet(client);
            if (ws != null) { ws.abort(); if(dialer!=null) dialer.release(ws); }
        }
    }

    private void bridge(Socket client, RawWebSocket ws, CryptoContext crypto,
                        TgWebMsgSplitter splitter) throws InterruptedException {
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean clientEnded = new AtomicBoolean(false);
        AtomicReference<String> wsFailure = new AtomicReference<>(null);
        InputStream in;
        OutputStream out;
        try {
            in = client.getInputStream();
            out = client.getOutputStream();
        } catch (IOException e) {
            return;
        }

        Thread up = new Thread(() -> {
            byte[] buf = new byte[READ_BUFFER];
            try {
                while (!done.get()) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException clientReadError) {
                        clientEnded.set(true);
                        break;
                    }
                    if (n <= 0) {
                        clientEnded.set(true);
                        break;
                    }
                    byte[] encryptedClient = n == buf.length ? buf : Arrays.copyOf(buf, n);
                    byte[][] packet=NativeCrypto.transcodePacket(crypto.clientDecrypt,crypto.telegramEncrypt,encryptedClient);
                    List<byte[]> messages=splitter.split(packet[1],packet[0]);
                    if (!messages.isEmpty()) ws.sendBatch(messages);
                }
                if (clientEnded.get() && !done.get()) {
                    List<byte[]> tail = splitter.flush();
                    if (!tail.isEmpty()) ws.sendBatch(tail);
                }
            } catch (Exception e) {
                if (!clientEnded.get())
                    wsFailure.compareAndSet(null, "WebSocket send failed: " + safeMessage(e));
            } finally {
                done.set(true);
                ws.abort();
            }
        }, "tg-route-up");

        Runnable download = () -> {
            try {
                while (!done.get()) {
                    byte[] payload = ws.recv();
                    if (payload == null) {
                        if (!clientEnded.get())
                            wsFailure.compareAndSet(null, "WebSocket closed");
                        break;
                    }
                    if (payload.length == 0) continue;
                    byte[] encryptedClient=NativeCrypto.transcode(crypto.telegramDecrypt,crypto.clientEncrypt,payload);
                    out.write(encryptedClient);
                    out.flush();
                }
            } catch (Exception e) {
                if (!clientEnded.get())
                    wsFailure.compareAndSet(null, "WebSocket receive failed: " + safeMessage(e));
            } finally {
                done.set(true);
                closeQuiet(client);
                ws.abort();
            }
        };

        up.setDaemon(true);
        up.start();
        try {
            download.run();
        } finally {
            closeQuiet(client);
            ws.abort();
            up.join();
        }

        String failure = wsFailure.get();
        if (failure != null && running && !clientEnded.get()) markRouteError(failure);
    }

    private void bridgeTcp(Socket client, int dc, byte[] init, CryptoContext crypto, android.net.Network vpn) throws Exception {
        String[] ips={"","149.154.175.50","149.154.167.51","149.154.175.100","149.154.167.91","149.154.171.5"};
        int d=RouteDialer.dcNum(dc);
        if(d<1||d>5)throw new IOException("Неизвестный DC");
        Socket remote=new Socket();remoteSockets.add(remote);
        try {
            if (vpn != null) vpn.bindSocket(remote);
            remote.connect(new InetSocketAddress(ips[d],443),4000);remote.setTcpNoDelay(true);remote.setKeepAlive(true);
            remote.getOutputStream().write(init);remote.getOutputStream().flush();
            markRouteReady();
            Thread upload = new Thread(() -> {
                try {
                    pipe(client.getInputStream(), remote.getOutputStream(), crypto.clientDecrypt, crypto.telegramEncrypt);
                } catch (Exception ignored) {
                } finally {
                    closeQuiet(remote);
                }
            }, "tcp-up");
            upload.start();
            try {
                pipe(remote.getInputStream(), client.getOutputStream(), crypto.telegramDecrypt, crypto.clientEncrypt);
            } finally {
                closeQuiet(client);
                closeQuiet(remote);
                upload.join();
            }
        }finally{remoteSockets.remove(remote);closeQuiet(remote);}
    }
    private void pipe(InputStream in,OutputStream out,NativeCrypto.Stream dec,NativeCrypto.Stream enc)throws Exception {
        byte[] b=new byte[65536];int n;
        while(running&&(n=in.read(b))>0){byte[] value=NativeCrypto.transcode(dec,enc,n==b.length?b:Arrays.copyOf(b,n));out.write(value);out.flush();}
    }

    private void reportFatal(String message) {
        if (!fatalReported.compareAndSet(false, true)) return;
        OnFatalError l = fatalErrorListener;
        if (l != null) l.onFatalError(message == null ? "Ошибка обхода" : message);
    }

    private static final class Handshake {
        final int dc;
        final boolean media;
        final int protocolTagInt;
        final byte[] protocolTag;
        final byte[] clientMaterial;

        Handshake(int dc, boolean media, int protocolTagInt, byte[] protocolTag,
                  byte[] clientMaterial) {
            this.dc = dc;
            this.media = media;
            this.protocolTagInt = protocolTagInt;
            this.protocolTag = protocolTag;
            this.clientMaterial = clientMaterial;
        }
    }

    private static Handshake parseHandshake(byte[] raw,byte[] secret){
        int[] values=NativeCrypto.parseHandshake(raw,secret);
        if(values==null)return null;
        int protocol=values[2];
        byte[] tag={(byte)protocol,(byte)(protocol>>>8),(byte)(protocol>>>16),(byte)(protocol>>>24)};
        return new Handshake(values[0],values[1]!=0,protocol,tag,Arrays.copyOfRange(raw,8,56));
    }

    private static final class CryptoContext implements AutoCloseable {
        final NativeCrypto.Stream clientDecrypt;
        final NativeCrypto.Stream clientEncrypt;
        final NativeCrypto.Stream telegramEncrypt;
        final NativeCrypto.Stream telegramDecrypt;

        CryptoContext(NativeCrypto.Stream clientDecrypt, NativeCrypto.Stream clientEncrypt,
                      NativeCrypto.Stream telegramEncrypt, NativeCrypto.Stream telegramDecrypt) {
            this.clientDecrypt = clientDecrypt;
            this.clientEncrypt = clientEncrypt;
            this.telegramEncrypt = telegramEncrypt;
            this.telegramDecrypt = telegramDecrypt;
        }
        public void close(){clientDecrypt.close();clientEncrypt.close();telegramEncrypt.close();telegramDecrypt.close();}
    }

    private static CryptoContext buildCryptoContext(byte[] clientMaterial, byte[] secret,
                                                    byte[] relayInit) throws Exception {
        byte[] clientPrekey = Arrays.copyOfRange(clientMaterial, 0, 32);
        byte[] clientIv = Arrays.copyOfRange(clientMaterial, 32, 48);
        NativeCrypto.Stream clientDec = newStream(sha256(concat(clientPrekey, secret)), clientIv);
        crypt(clientDec, ZERO_64);

        byte[] reversedClient = reverse(clientMaterial);
        byte[] clientEncPrekey = Arrays.copyOfRange(reversedClient, 0, 32);
        byte[] clientEncIv = Arrays.copyOfRange(reversedClient, 32, 48);
        NativeCrypto.Stream clientEnc = newStream(sha256(concat(clientEncPrekey, secret)), clientEncIv);

        byte[] relayKey = Arrays.copyOfRange(relayInit, 8, 40);
        byte[] relayIv = Arrays.copyOfRange(relayInit, 40, 56);
        NativeCrypto.Stream tgEnc = newStream(relayKey, relayIv);
        crypt(tgEnc, ZERO_64);

        byte[] reversedRelay = reverse(Arrays.copyOfRange(relayInit, 8, 56));
        NativeCrypto.Stream tgDec = newStream(Arrays.copyOfRange(reversedRelay, 0, 32),
                Arrays.copyOfRange(reversedRelay, 32, 48));

        return new CryptoContext(clientDec, clientEnc, tgEnc, tgDec);
    }

    private static byte[] generateRelayInit(byte[] protocolTag, int dcIndex) throws Exception {
        byte[] raw = new byte[HANDSHAKE_LEN];
        while (true) {
            RNG.nextBytes(raw);
            if ((raw[0] & 0xFF) == 0xEF) continue;
            if (startsWith(raw, "HEAD") || startsWith(raw, "POST") || startsWith(raw, "GET ")) continue;
            if (startsWith(raw, new byte[]{(byte) 0xEE, (byte) 0xEE, (byte) 0xEE, (byte) 0xEE})
                    || startsWith(raw, new byte[]{(byte) 0xDD, (byte) 0xDD, (byte) 0xDD, (byte) 0xDD})
                    || startsWith(raw, new byte[]{0x16, 0x03, 0x01, 0x02})) continue;
            if (allZero(raw, 4, 8)) continue;
            break;
        }

        byte[] key = Arrays.copyOfRange(raw, 8, 40);
        byte[] iv = Arrays.copyOfRange(raw, 40, 56);
        byte[] encryptedFull;
        try(NativeCrypto.Stream stream=newStream(key,iv)){encryptedFull=crypt(stream,raw);}
        byte[] result = Arrays.copyOf(raw, raw.length);
        byte[] tail = new byte[8];
        System.arraycopy(protocolTag, 0, tail, 0, 4);
        tail[4] = (byte) (dcIndex & 0xFF);
        tail[5] = (byte) ((dcIndex >>> 8) & 0xFF);
        tail[6] = (byte) RNG.nextInt(256);
        tail[7] = (byte) RNG.nextInt(256);
        for (int i = 0; i < 8; i++) {
            byte keystream = (byte) (encryptedFull[56 + i] ^ raw[56 + i]);
            result[56 + i] = (byte) (tail[i] ^ keystream);
        }
        return result;
    }

    private static NativeCrypto.Stream newStream(byte[] key,byte[] iv){return new NativeCrypto.Stream(key,iv);}
    private static byte[] crypt(NativeCrypto.Stream stream,byte[] input){return stream.update(input);}

    public static String normalizeSecretHex(String value) {
        if (value == null) return "";
        String s = value.trim().toLowerCase(java.util.Locale.US);
        if ((s.startsWith("dd") || s.startsWith("ee")) && s.length() >= 34) s = s.substring(2, 34);
        if (s.length() != 32 || !s.matches("[0-9a-f]{32}")) return "";
        return s;
    }

    public static String generateSecretHex() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte v : b) sb.append(String.format(java.util.Locale.US, "%02x", v & 0xFF));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] reverse(byte[] in) {
        byte[] out = new byte[in.length];
        for (int i = 0; i < in.length; i++) out[i] = in[in.length - 1 - i];
        return out;
    }

    private static boolean startsWith(byte[] b, String ascii) {
        byte[] p;
        try { p = ascii.getBytes("US-ASCII"); }
        catch (Exception e) { return false; }
        if (b.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (b[i] != p[i]) return false;
        return true;
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        if (b == null || prefix == null || b.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (b[i] != prefix[i]) return false;
        return true;
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) if (b[i] != 0) return false;
        return true;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] out = new byte[length];
        int pos = 0;
        while (pos < length) {
            int n = in.read(out, pos, length - pos);
            if (n < 0) throw new IOException("EOF");
            pos += n;
        }
        return out;
    }

    private static void closeQuiet(Socket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }
}
