package com.giftloop.enrich;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The link-preview resolver refuses any non-public answer and never hands out unresolved addresses.
 */
class PublicOnlyDnsResolverTest {

    private static HostLookup answers(String... ips) {
        return host -> {
            InetAddress[] out = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) {
                out[i] = InetAddress.getByAddress(host, InetAddress.getByName(ips[i]).getAddress());
            }
            return out;
        };
    }

    /** All-public answers pass through unchanged. */
    @Test
    void allowsPublic() throws Exception {
        InetAddress[] out = new PublicOnlyDnsResolver(answers("93.184.216.34", "2606:4700::1"), false).resolve("shop.example");
        assertEquals(2, out.length);
    }

    /** One private address among public ones refuses the whole name. */
    @Test
    void refusesMixedAnswers() {
        PublicOnlyDnsResolver resolver = new PublicOnlyDnsResolver(answers("93.184.216.34", "10.0.0.5"), false);
        assertThrows(UnknownHostException.class, () -> resolver.resolve("shop.example"));
        PublicOnlyDnsResolver metadata = new PublicOnlyDnsResolver(answers("169.254.169.254"), false);
        assertThrows(UnknownHostException.class, () -> metadata.resolve("shop.example"));
    }

    /** Loopback only passes with the test switch on. */
    @Test
    void loopbackNeedsSwitch() throws Exception {
        assertThrows(UnknownHostException.class, () -> new PublicOnlyDnsResolver(answers("127.0.0.1"), false).resolve("x"));
        assertEquals(1, new PublicOnlyDnsResolver(answers("127.0.0.1"), true).resolve("x").length);
    }

    /** Unknown hosts and empty answers fail. */
    @Test
    void failsUnknownHosts() {
        HostLookup missing = host -> {
            throw new UnknownHostException(host);
        };
        assertThrows(UnknownHostException.class, () -> new PublicOnlyDnsResolver(missing, false).resolve("nope.example"));
        assertThrows(UnknownHostException.class,
                () -> new PublicOnlyDnsResolver(host -> new InetAddress[0], false).resolve("empty.example"));
    }

    /** Socket addresses are always resolved (never left for the socket to look up again). */
    @Test
    void socketAddressesAreResolved() throws Exception {
        List<InetSocketAddress> out = new PublicOnlyDnsResolver(answers("93.184.216.34"), false).resolve("shop.example", 443);
        assertEquals(1, out.size());
        assertFalse(out.get(0).isUnresolved());
        assertEquals(443, out.get(0).getPort());
        assertEquals("shop.example", new PublicOnlyDnsResolver(answers("93.184.216.34"), false)
                .resolveCanonicalHostname("shop.example"));
    }
}
