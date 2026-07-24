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
 *
 * <p><b>IPv6 /64 bucketing:</b> the resolved key is not always the exact address. A typical IPv6
 * subscriber controls an entire /64 prefix (2^64 addresses), so keying limits on the full address
 * would let a client rotate through fresh addresses to mint a new rate-limit / quota bucket per
 * request — bypassing the limits and flooding the per-IP maps until the fail-open threshold.
 * Therefore any IPv6 client address (from an XFF token or the socket peer alike) is normalized to
 * its /64 prefix: the low 64 bits are zeroed and the key is the canonical prefix text with a
 * {@code /64} suffix (e.g. {@code 2001:db8:1:2::/64}). IPv4 addresses — including IPv4-mapped
 * IPv6 ({@code ::ffff:a.b.c.d}) — keep exact-address keys.
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
            return remote == null ? "unknown" : bucketKey(remote);
        }
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] tokens = xff.split(",");
            // Walk right-to-left; the first token that is NOT a trusted proxy is the real client
            // (the address our edge observed and appended). Forged leftmost tokens are ignored.
            for (int i = tokens.length - 1; i >= 0; i--) {
                String candidate = normalize(tokens[i]);
                if (candidate == null) continue;
                if (!isTrusted(candidate)) return bucketKey(candidate);
            }
        }
        // Every hop was a trusted proxy (or XFF absent) — fall back to the socket peer.
        return bucketKey(remote);
    }

    /**
     * Converts a resolved client address into the rate-limit / quota key. IPv6 addresses are
     * bucketed by their /64 prefix (low 64 bits zeroed, canonical text + {@code "/64"}) so that
     * address rotation inside one delegated prefix cannot mint fresh buckets. IPv4 — including
     * IPv4-mapped IPv6, which the JDK parses to 4 bytes — keeps the exact address as the key.
     * Unparseable input is returned unchanged (one opaque key per distinct string).
     */
    private static String bucketKey(String ip) {
        InetAddress parsed;
        try {
            parsed = InetAddress.getByName(ip);
        } catch (UnknownHostException e) {
            return ip;
        }
        byte[] addr = parsed.getAddress();
        if (addr.length != 16) {
            // IPv4 (an IPv4-mapped IPv6 literal also lands here as an Inet4Address): exact address,
            // canonicalized so "::ffff:a.b.c.d" and "a.b.c.d" share one bucket.
            return parsed.getHostAddress();
        }
        Arrays.fill(addr, 8, 16, (byte) 0);
        return canonicalV6Prefix(addr) + "/64";
    }

    /**
     * RFC 5952 canonical text of a 16-byte IPv6 address whose low 64 bits are zero (so the
     * compressed {@code ::} run always covers at least the four trailing groups).
     */
    private static String canonicalV6Prefix(byte[] addr) {
        int[] groups = new int[4];
        for (int i = 0; i < 4; i++) {
            groups[i] = ((addr[2 * i] & 0xFF) << 8) | (addr[2 * i + 1] & 0xFF);
        }
        // Extend the trailing zero run (groups 4..7, always zero here) leftwards; it is always the
        // longest run, so RFC 5952 compresses exactly this run.
        int keep = 4;
        while (keep > 0 && groups[keep - 1] == 0) keep--;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) sb.append(':');
            sb.append(Integer.toHexString(groups[i]));
        }
        return sb.append("::").toString();
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
