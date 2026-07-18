package com.pdfconduit.web.support;

import com.pdfconduit.web.config.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves the client IP used as the rate-limit / quota key. The backend sits behind an edge
 * proxy (nginx/Caddy), so the socket peer is a proxy, not the real caller. Trusting the
 * <em>leftmost</em> {@code X-Forwarded-For} token is a vulnerability: any client can forge that
 * header to mint a fresh rate-limit / quota bucket per request (full bypass) and flood the
 * per-IP maps. Instead we trust XFF only when the socket peer is a configured trusted proxy, and
 * take the <em>rightmost</em> token that is not itself a trusted proxy — the address our own edge
 * actually observed. When the peer is untrusted, XFF is ignored entirely and the socket peer is
 * used.
 */
@Component
public final class ClientIp {

    private static final Logger log = LoggerFactory.getLogger(ClientIp.class);

    private final List<Cidr> trustedProxies;

    public ClientIp(WebProperties props) {
        this.trustedProxies = parseCidrs(props.trustedProxies());
    }

    public String resolve(HttpServletRequest req) {
        String remote = normalize(req.getRemoteAddr());
        // Only consult X-Forwarded-For if the immediate peer is a proxy we trust to set it.
        if (remote == null || !isTrusted(remote)) {
            return remote == null ? "unknown" : remote;
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] tokens = xff.split(",");
            // Walk right-to-left; the first token that is NOT a trusted proxy is the real client
            // (the address our edge observed and appended). Forged leftmost tokens are ignored.
            for (int i = tokens.length - 1; i >= 0; i--) {
                String candidate = normalize(tokens[i]);
                if (candidate == null) continue;
                if (!isTrusted(candidate)) return candidate;
            }
        }
        // Every hop was a trusted proxy (or XFF absent) — fall back to the socket peer.
        return remote;
    }

    private boolean isTrusted(String ip) {
        byte[] addr = bytesOf(ip);
        if (addr == null) return false;
        for (Cidr c : trustedProxies) {
            if (c.matches(addr)) return true;
        }
        return false;
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty()) return null;
        // Strip an optional :port on a plain IPv4 literal (e.g. "203.0.113.5:1234").
        if (s.indexOf(':') >= 0 && s.indexOf('.') >= 0 && s.indexOf(':') == s.lastIndexOf(':')) {
            s = s.substring(0, s.indexOf(':'));
        }
        // Strip IPv6 brackets ("[::1]" → "::1").
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        return s.isEmpty() ? null : s;
    }

    private static byte[] bytesOf(String ip) {
        try {
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static List<Cidr> parseCidrs(List<String> specs) {
        List<Cidr> out = new ArrayList<>();
        if (specs == null) return out;
        for (String spec : specs) {
            Cidr c = Cidr.parse(spec);
            if (c != null) out.add(c);
            else log.warn("Ignoring malformed trusted-proxy CIDR: {}", spec);
        }
        return out;
    }

    /** A parsed CIDR (network address + prefix length) with a byte-prefix match. */
    private record Cidr(byte[] network, int prefixBits) {
        static Cidr parse(String spec) {
            if (spec == null || spec.isBlank()) return null;
            String s = spec.strip();
            int slash = s.indexOf('/');
            String host = slash >= 0 ? s.substring(0, slash) : s;
            byte[] addr;
            try {
                addr = InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException e) {
                return null;
            }
            int maxBits = addr.length * 8;
            int bits = maxBits;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(s.substring(slash + 1).strip());
                } catch (NumberFormatException e) {
                    return null;
                }
                if (bits < 0 || bits > maxBits) return null;
            }
            return new Cidr(addr, bits);
        }

        boolean matches(byte[] addr) {
            if (addr.length != network.length) return false; // v4 vs v6 mismatch
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) return false;
            }
            int remaining = prefixBits % 8;
            if (remaining == 0) return true;
            int mask = 0xFF << (8 - remaining) & 0xFF;
            return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Cidr c && prefixBits == c.prefixBits && Arrays.equals(network, c.network);
        }

        @Override public int hashCode() {
            return Arrays.hashCode(network) * 31 + prefixBits;
        }
    }
}
