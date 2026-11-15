package com.giftloop.enrich;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * DNS for link previews: resolves once and refuses names with any non-public address
 * ({@link SafeFetcher#isPublic}). The HTTP client connects to exactly the addresses returned
 * here and does no lookup of its own, so a DNS server can't pass a public answer to a check
 * and then hand the connection a private one (DNS rebinding).
 */
final class PublicOnlyDnsResolver implements DnsResolver {

    private final HostLookup lookup;
    private final boolean allowLoopback;

    /**
     * Creates the resolver.
     *
     * @param lookup        underlying DNS lookup
     * @param allowLoopback let loopback through (tests only)
     */
    PublicOnlyDnsResolver(HostLookup lookup, boolean allowLoopback) {
        this.lookup = lookup;
        this.allowLoopback = allowLoopback;
    }

    /**
     * Resolves a host, refusing it if any address is non-public.
     *
     * @param host name or IP literal
     * @return the checked addresses
     * @throws UnknownHostException if it doesn't resolve or any address is non-public
     */
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] addresses = lookup.lookup(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException("no addresses for " + host);
        }
        for (InetAddress address : addresses) {
            if (!(allowLoopback && address.isLoopbackAddress()) && !SafeFetcher.isPublic(address)) {
                throw new UnknownHostException("non-public address for " + host);
            }
        }
        return addresses;
    }

    /**
     * Socket addresses for a connection. Overridden so the result is always resolved: the
     * interface default falls back to an unresolved address, which the socket would look up
     * again, unchecked.
     *
     * @param host name or IP literal
     * @param port port
     * @return resolved, checked socket addresses
     * @throws UnknownHostException if it doesn't resolve or any address is non-public
     */
    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        return Arrays.stream(resolve(host)).map(a -> new InetSocketAddress(a, port)).toList();
    }

    /**
     * No canonical-name lookup (it would be another, unchecked query).
     *
     * @param host host name
     * @return the host unchanged
     */
    @Override
    public String resolveCanonicalHostname(String host) {
        return host;
    }
}
