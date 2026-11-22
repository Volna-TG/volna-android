package app.volna;

import java.io.IOException;
import java.net.*;


public final class ListenerPort {
    static ServerSocket bind(int preferred) throws IOException {
        if (preferred < 0 || preferred > 65535) throw new IllegalArgumentException("Неверный порт");
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", preferred));
            return socket;
        } catch (IOException failure) {
            try { socket.close(); } catch (IOException ignored) {}
            if (!isOccupied(failure)) throw failure;
            ServerSocket fallback = new ServerSocket();
            try {
                fallback.setReuseAddress(true);
                fallback.bind(new InetSocketAddress("127.0.0.1", 0));
                return fallback;
            } catch (IOException e) {
                try { fallback.close(); } catch (IOException ignored) {}
                throw e;
            }
        }
    }
    private static boolean isOccupied(Throwable e) {
        for (Throwable t=e; t!=null; t=t.getCause()) {
            if (t instanceof BindException) return true;
            String message=t.getMessage();
            if (message!=null && (message.contains("EADDRINUSE") || message.toLowerCase(java.util.Locale.US).contains("address already in use"))) return true;
        }
        return false;
    }
}
