package com.pdfconduit.web;

import com.pdfconduit.web.config.WebProperties;
import com.pdfconduit.web.support.ClientIp;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Proves the IPv6 /64 bucketing of the rate-limit / quota key (audit item 23): a typical IPv6
 * subscriber owns a whole /64, so keying limits on the exact address would let them rotate
 * addresses to mint a fresh bucket per request. {@link ClientIp} must therefore collapse any
 * IPv6 client — whether it arrives as an X-Forwarded-For token or as the socket peer — onto its
 * canonical /64 prefix key, while IPv4 (including IPv4-mapped IPv6) keeps exact-address keys.
 */
class ClientIpV6BucketTest {

    private static ClientIp clientIp() {
        // Only 127.0.0.1 (the socket peer in the XFF-path tests) is a trusted proxy.
        return new ClientIp(new WebProperties(
            null, null, null, null, null, null, null, null, null, null, null,
            List.of("127.0.0.1/32")));
    }

    /** Resolves via the XFF-token path: trusted peer 127.0.0.1 forwards {@code client}. */
    private static String viaXff(String client) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", client);
        return clientIp().resolve(req);
    }

    /** Resolves via the socket-peer fallback: {@code client} connects directly (untrusted peer). */
    private static String viaSocketPeer(String client) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(client);
        return clientIp().resolve(req);
    }

    @Test
    void sameIpv6Slash64_sharesOneBucketKey() {
        // Rotating the low 64 bits (the attack) must NOT change the key.
        assertEquals("2001:db8:1:2::/64", viaXff("2001:db8:1:2:aaaa:bbbb:cccc:dddd"));
        assertEquals("2001:db8:1:2::/64", viaXff("2001:db8:1:2::17"));
        // Same rule on the socket-peer fallback path.
        assertEquals("2001:db8:1:2::/64", viaSocketPeer("2001:db8:1:2:ffff:ffff:ffff:ffff"));
    }

    @Test
    void differentIpv6Slash64_getDistinctBucketKeys() {
        String a = viaXff("2001:db8:1:2::1");
        String b = viaXff("2001:db8:1:3::1");
        assertEquals("2001:db8:1:2::/64", a);
        assertEquals("2001:db8:1:3::/64", b);
        assertNotEquals(a, b);
    }

    @Test
    void ipv4_keepsExactAddressKey() {
        assertEquals("203.0.113.9", viaXff("203.0.113.9"));
        assertEquals("203.0.113.9", viaSocketPeer("203.0.113.9"));
        // Distinct IPv4 addresses are NOT collapsed onto a shared prefix bucket.
        assertNotEquals(viaXff("203.0.113.9"), viaXff("203.0.113.10"));
    }

    @Test
    void ipv4MappedIpv6_keysAsTheIpv4Address() {
        // "::ffff:a.b.c.d" is an IPv4 client in IPv6 clothing — exact IPv4 key, no /64 truncation,
        // and the same bucket as the plain dotted-quad form.
        assertEquals("203.0.113.9", viaXff("::ffff:203.0.113.9"));
        assertEquals("203.0.113.9", viaSocketPeer("::ffff:203.0.113.9"));
    }
}
