package com.giftloop.enrich;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * DNS lookup used by link previews. Exists so tests can fake DNS answers (e.g. rebinding).
 */
@FunctionalInterface
public interface HostLookup {

    /** The system resolver. */
    HostLookup SYSTEM = InetAddress::getAllByName;

    /**
     * Resolves a host name or IP literal.
     *
     * @param host name or literal
     * @return addresses (never empty)
     * @throws UnknownHostException if it doesn't resolve
     */
    InetAddress[] lookup(String host) throws UnknownHostException;
}
