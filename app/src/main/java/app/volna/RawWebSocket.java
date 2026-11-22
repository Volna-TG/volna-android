package app.volna;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;



public class RawWebSocket {

    private static final int OP_CONT   = 0x0;
    private static final int OP_TEXT   = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE  = 0x8;
    private static final int OP_PING   = 0x9;
    private static final int OP_PONG   = 0xA;
    private static final int MAX_MESSAGE_BYTES = 16 * 1024 * 1024;

    private final InputStream in;
    private final OutputStream out;
    private final Socket socket;
    private volatile boolean closed = false;
    private static final SecureRandom rng = new SecureRandom();

    RawWebSocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new java.io.BufferedInputStream(socket.getInputStream(), 16384);
        this.out = new java.io.BufferedOutputStream(socket.getOutputStream(), 16384);
    }

    static RawWebSocket cloudflare80(String host, int timeout) throws Exception {
        Socket raw = new Socket();
        try {
            String address = Dns.resolve(host);
            if (address == null) throw new IOException("DNS: " + host);
            raw.connect(new java.net.InetSocketAddress(address, 80), timeout);
            raw.setSoTimeout(timeout);
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);
            RawWebSocket ws = new RawWebSocket(raw);
            byte[] keyBytes = new byte[16];
            rng.nextBytes(keyBytes);
            String wsKey = java.util.Base64.getEncoder().encodeToString(keyBytes);
            String expectedAccept = websocketAccept(wsKey);

            StringBuilder req = new StringBuilder();
            req.append("GET /apiws HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Upgrade: websocket\r\n")
                    .append("Connection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n")
                    .append("Sec-WebSocket-Protocol: binary\r\n")
                    .append("Origin: https://web.telegram.org\r\n")
                    .append("User-Agent: Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36\r\n");
            req.append("\r\n");
            ws.out.write(req.toString().getBytes("UTF-8"));
            ws.out.flush();

            int statusCode = 0;
            String statusLine = "";
            boolean firstLine = true;
            String upgradeHeader = "";
            String connectionHeader = "";
            String acceptHeader = "";
            int headerLines = 0;
            int headerChars = 0;
            while (true) {
                String line = readLine(ws.in);
                if (line == null || line.isEmpty()) break;
                headerLines++;
                headerChars += line.length();
                if (headerLines > 64 || headerChars > 64 * 1024)
                    throw new IOException("WS response headers too large");
                if (firstLine) {
                    statusLine = line;
                    String[] parts = line.split(" ", 3);
                    if (parts.length >= 2) {
                        try { statusCode = Integer.parseInt(parts[1]); }
                        catch (NumberFormatException ignored) {}
                    }
                    firstLine = false;
                } else {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                        String value = line.substring(colon + 1).trim();
                        if ("upgrade".equals(name)) upgradeHeader = value;
                        else if ("connection".equals(name)) connectionHeader = value;
                        else if ("sec-websocket-accept".equals(name)) acceptHeader = value;
                    }
                }
            }

            if (statusCode == 101) {
                boolean upgradeOk = "websocket".equalsIgnoreCase(upgradeHeader);
                boolean connectionOk = connectionHeader.toLowerCase(Locale.US).contains("upgrade");
                boolean acceptOk = constantTimeEquals(expectedAccept, acceptHeader);
                if (!upgradeOk || !connectionOk || !acceptOk) {
                    ws.closeQuiet();
                    throw new IOException("Invalid WebSocket upgrade response");
                }
                raw.setSoTimeout(0);
                return ws;
            }

            ws.closeQuiet();
            if (statusCode == 301 || statusCode == 302 || statusCode == 303 ||
                    statusCode == 307 || statusCode == 308) {
                throw new WsRedirectException(statusCode);
            }
            String suffix = statusLine == null || statusLine.isEmpty() ? "" : " (" + statusLine + ")";
            throw new IOException("WS handshake failed: " + statusCode + suffix);
        } catch (Exception e) {
            try { raw.close(); } catch (Exception ignored) {}
            throw e;
        }
    }


    private static String websocketAccept(String key) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] digest = sha1.digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                .getBytes("ISO-8859-1"));
        return java.util.Base64.getEncoder().encodeToString(digest);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        try {
            return MessageDigest.isEqual(a.trim().getBytes("US-ASCII"), b.trim().getBytes("US-ASCII"));
        } catch (Exception e) {
            return false;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') sb.setLength(sb.length() - 1);
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("WS header line too long");
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    public void send(byte[] data) throws IOException {
        if (closed) throw new IOException("closed");
        synchronized (out) {
            out.write(buildFrame(OP_BINARY, data, true));
            out.flush();
        }
    }

    public void sendBatch(java.util.List<byte[]> parts) throws IOException {
        if (closed) throw new IOException("closed");
        synchronized (out) {
            for (byte[] p : parts) out.write(buildFrame(OP_BINARY, p, true));
            out.flush();
        }
    }

    public byte[] recv() throws IOException {
        ByteArrayOutputStream fragmented = null;
        int initialOpcode = -1;
        while (!closed) {
            Frame f = readFrame();

            if (f.opcode == OP_CLOSE) {
                closed = true;
                try {
                    byte[] closeData = f.payload.length >= 2 ? Arrays.copyOf(f.payload, 2) : new byte[0];
                    synchronized (out) {
                        out.write(buildFrame(OP_CLOSE, closeData, true));
                        out.flush();
                    }
                } catch (Exception ignored) {}
                closeQuiet();
                return null;
            }
            if (f.opcode == OP_PING) {
                synchronized (out) {
                    out.write(buildFrame(OP_PONG, f.payload, true));
                    out.flush();
                }
                continue;
            }
            if (f.opcode == OP_PONG) continue;

            if (f.opcode == OP_TEXT || f.opcode == OP_BINARY) {
                if (f.fin) return f.payload;
                fragmented = new ByteArrayOutputStream(Math.min(MAX_MESSAGE_BYTES, Math.max(1024, f.payload.length * 2)));
                fragmented.write(f.payload, 0, f.payload.length);
                initialOpcode = f.opcode;
                continue;
            }

            if (f.opcode == OP_CONT) {
                if (fragmented == null || initialOpcode < 0) throw new IOException("unexpected WS continuation");
                if (fragmented.size() + f.payload.length > MAX_MESSAGE_BYTES) throw new IOException("WS message too large");
                fragmented.write(f.payload, 0, f.payload.length);
                if (f.fin) return fragmented.toByteArray();
                continue;
            }
            
        }
        return null;
    }

    public void abort() { closed=true; closeQuiet(); }

    private void closeQuiet() { try { socket.close(); } catch (Exception ignored) {} }

    private Frame readFrame() throws IOException {
        byte[] h = readExactly(2);
        boolean fin = (h[0] & 0x80) != 0;
        int opcode = h[0] & 0x0F;
        boolean masked = (h[1] & 0x80) != 0;
        long length = h[1] & 0x7F;
        if (length == 126) {
            byte[] ext = readExactly(2);
            length = ((ext[0] & 0xFFL) << 8) | (ext[1] & 0xFFL);
        } else if (length == 127) {
            byte[] ext = readExactly(8);
            length = ByteBuffer.wrap(ext).getLong();
            if (length < 0) throw new IOException("invalid WS length");
        }
        if (length > MAX_MESSAGE_BYTES) throw new IOException("WS frame too large: " + length);
        if ((opcode & 0x8) != 0 && (!fin || length > 125)) throw new IOException("invalid WS control frame");
        byte[] mask = masked ? readExactly(4) : null;
        byte[] payload = readExactly((int) length);
        if (masked) payload = xorMask(payload, mask);
        return new Frame(fin, opcode, payload);
    }

    private byte[] readExactly(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r;
            try { r = in.read(buf, off, n - off); }
            catch(java.net.SocketTimeoutException e) { if(off>0)throw new IOException("Incomplete WebSocket frame",e);throw e; }
            if (r == -1) throw new IOException("EOF");
            off += r;
        }
        return buf;
    }

    private static byte[] xorMask(byte[] data, byte[] mask) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) result[i] = (byte) (data[i] ^ mask[i % 4]);
        return result;
    }

    private static byte[] buildFrame(int opcode, byte[] data, boolean mask) {
        int headerLen = 2;
        int len = data.length;
        if (len >= 126 && len < 65536) headerLen += 2;
        else if (len >= 65536) headerLen += 8;
        if (mask) headerLen += 4;
        byte[] frame = new byte[headerLen + len];
        int pos = 0;
        frame[pos++] = (byte) (0x80 | opcode);
        int maskBit = mask ? 0x80 : 0;
        if (len < 126) {
            frame[pos++] = (byte) (maskBit | len);
        } else if (len < 65536) {
            frame[pos++] = (byte) (maskBit | 126);
            frame[pos++] = (byte) ((len >> 8) & 0xFF);
            frame[pos++] = (byte) (len & 0xFF);
        } else {
            frame[pos++] = (byte) (maskBit | 127);
            byte[] lb = ByteBuffer.allocate(8).putLong(len).array();
            System.arraycopy(lb, 0, frame, pos, 8); pos += 8;
        }
        if (mask) {
            byte[] mk = new byte[4]; rng.nextBytes(mk);
            System.arraycopy(mk, 0, frame, pos, 4); pos += 4;
            for (int i = 0; i < len; i++) frame[pos + i] = (byte) (data[i] ^ mk[i % 4]);
        } else {
            System.arraycopy(data, 0, frame, pos, len);
        }
        return frame;
    }

    private static final class Frame {
        final boolean fin; final int opcode; final byte[] payload;
        Frame(boolean fin, int opcode, byte[] payload) { this.fin = fin; this.opcode = opcode; this.payload = payload; }
    }

    public static class WsRedirectException extends IOException {
        public final int statusCode;
        public WsRedirectException(int code) { super("Redirect " + code); this.statusCode = code; }
    }
}
