package app.volna;

import android.os.SystemClock;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class RouteDialer {
    static final String[] BASES={"pclead.co.uk","offshor.co.uk","cakeisalie.co.uk","noskomnadzor.co.uk","lovetrue.co.uk","sorokdva.co.uk","pyatdesyatdva.co.uk","kartoshka.co.uk","sorokodin.co.uk","pyatdesyatodin.co.uk","notelega.co.uk","ebally.co.uk","nebally.co.uk","havegreatday.co.uk","pomogite.co.uk","fixtelega.co.uk","sadnews.co.uk","onedaychamp.co.uk","stopblocking.co.uk","nothingthere.co.uk"};
    private final int offset = new SecureRandom().nextInt(BASES.length);
    private final Map<Integer, String> preferred = new ConcurrentHashMap<>();
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final Set<RawWebSocket> opened = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    static int dcNum(int dc) { return dc == 203 ? 2 : dc; }

    RawWebSocket open(int dc, int timeout) throws Exception {
        int number = dcNum(dc);
        long deadline = SystemClock.elapsedRealtime() + timeout;
        String saved = preferred.get(number);
        if (saved != null) {
            RawWebSocket socket = connect(saved, number, deadline);
            if (socket != null) return socket;
        }
        for (int i = 0; i < BASES.length; i++) {
            String host = "kws" + number + "." + BASES[(offset + i) % BASES.length];
            if (host.equals(saved)) continue;
            RawWebSocket socket = connect(host, number, deadline);
            if (socket != null) return socket;
        }
        throw new IOException("Стандартный маршрут недоступен в текущей сети");
    }

    private RawWebSocket connect(String host, int dc, long deadline) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (closed || Thread.currentThread().isInterrupted() || now >= deadline)
            throw new IOException("Поиск маршрута завершён");
        if (retryAt.getOrDefault(host, 0L) > now) return null;
        RawWebSocket socket;
        try {
            socket = RawWebSocket.cloudflare80(host, (int) Math.min(2200, deadline - now));
        } catch (Exception failure) {
            retryAt.put(host, now + 30000);
            return null;
        }
        opened.add(socket);
        if (closed) {
            release(socket);
            socket.abort();
            throw new IOException("Подключение остановлено");
        }
        retryAt.remove(host);
        preferred.put(dc, host);
        return socket;
    }

    void networkChanged() {
        retryAt.clear();
        Dns.clear();
    }

    void release(RawWebSocket socket) { opened.remove(socket); }

    void close() {
        closed = true;
        for (RawWebSocket socket : opened) socket.abort();
        opened.clear();
    }
}
