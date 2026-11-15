package com.giftloop.enrich;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.http.nio.support.AsyncRequestBuilder;
import org.apache.hc.core5.http2.HttpVersionPolicy;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.net.InetAddressUtils;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches user-supplied URLs (product pages and their images) without letting them reach
 * the server's own network (SSRF).
 *
 * <ul>
 *   <li>http/https on the default ports only; no user info in the URL.</li>
 *   <li>Every resolved address must be public: no loopback, private, link-local (cloud
 *       metadata), CGNAT, multicast, unique-local or reserved ranges. DNS is resolved once per
 *       connection by {@link PublicOnlyDnsResolver} and the connection goes to exactly those
 *       addresses, so DNS rebinding can't swap in a private one after the check.</li>
 *   <li>Redirects are followed by hand (at most {@value #MAX_REDIRECTS}) and each hop is
 *       re-checked.</li>
 *   <li>Short timeouts, a size cap per kind, and a content-type check. Pages over the cap are
 *       truncated (product details sit near the top; Amazon pages run past 2 MB); images over
 *       the cap are refused.</li>
 * </ul>
 *
 * <p>Transport is the Apache HttpClient 5 async client: it takes a pluggable DNS resolver (the
 * JDK client always does its own lookup) and speaks HTTP/2. HTTP/2 matters: Cloudflare-fronted
 * stores (e.g. Allbirds) answer Java's TLS over HTTP/1.1 with 429. No {@code Accept-Encoding}
 * is sent (left to itself the client advertises every codec on the classpath, another bot
 * signal), and no proxy settings are read, so nothing routes around the resolver.
 */
@ApplicationScoped
public class SafeFetcher {

    /** Most redirects followed. */
    static final int MAX_REDIRECTS = 3;
    private static final Timeout STEP_TIMEOUT = Timeout.ofSeconds(3);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(6);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0 Safari/537.36 GiftLoopLinkPreview/1.0";
    private static final Logger LOG = Logger.getLogger(SafeFetcher.class);

    /**
     * What is being fetched: decides the accepted content types and size cap.
     */
    public enum Kind {
        /** A product page; anything past the cap is dropped. */
        HTML(1_536 * 1024, true),
        /** A product photo; over the cap fails. */
        IMAGE(5 * 1024 * 1024, false);

        private final int maxBytes;
        private final boolean truncates;

        Kind(int maxBytes, boolean truncates) {
            this.maxBytes = maxBytes;
            this.truncates = truncates;
        }

        /**
         * Whether bodies over the cap are cut rather than refused.
         *
         * @return true for pages
         */
        boolean truncates() {
            return truncates;
        }

        /**
         * Size cap for this kind.
         *
         * @return largest accepted body in bytes
         */
        public int maxBytes() {
            return maxBytes;
        }

        boolean accepts(String contentType) {
            String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            return this == HTML
                    ? type.startsWith("text/html") || type.startsWith("application/xhtml+xml")
                    : type.startsWith("image/");
        }
    }

    /**
     * A fetched body.
     *
     * @param uri         final URL after redirects
     * @param contentType response content type
     * @param body        body bytes (within the kind's cap)
     */
    public record Fetched(URI uri, String contentType, byte[] body) {
    }

    /**
     * The URL was refused or could not be fetched. The message is for logs only.
     */
    public static class FetchException extends Exception {
        /**
         * Creates the exception.
         *
         * @param message reason (for logs)
         */
        public FetchException(String message) {
            super(message);
        }
    }

    /**
     * Test-only switch (set in the test profile): lets loopback hosts through so tests can
     * serve fixtures from a local server. Every other check still applies.
     */
    private final boolean allowLoopback;
    /** Test-only: allow non-default ports (local fixture servers use random ports). */
    private final boolean anyPort;
    private final CloseableHttpAsyncClient client;

    /**
     * Creates the fetcher.
     *
     * @param allowLoopback {@code giftloop.link-preview.allow-loopback}; never set outside tests.
     *                      Also allows non-default ports, which local fixture servers need.
     */
    @Inject
    public SafeFetcher(@ConfigProperty(name = "giftloop.link-preview.allow-loopback", defaultValue = "false")
                       boolean allowLoopback) {
        this(allowLoopback, allowLoopback, HostLookup.SYSTEM);
    }

    /**
     * Creates the fetcher with test knobs.
     *
     * @param allowLoopback let loopback addresses through
     * @param anyPort       allow non-default ports
     * @param lookup        DNS lookup
     */
    SafeFetcher(boolean allowLoopback, boolean anyPort, HostLookup lookup) {
        this.allowLoopback = allowLoopback;
        this.anyPort = anyPort;
        this.client = HttpAsyncClients.custom()
                .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                        .setDnsResolver(new PublicOnlyDnsResolver(lookup, allowLoopback))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(STEP_TIMEOUT)
                                .setSocketTimeout(STEP_TIMEOUT)
                                .build())
                        .setDefaultTlsConfig(TlsConfig.custom()
                                .setVersionPolicy(HttpVersionPolicy.NEGOTIATE)
                                .build())
                        .build())
                .setIOReactorConfig(IOReactorConfig.custom().setIoThreadCount(2).build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setRedirectsEnabled(false)
                        .setResponseTimeout(STEP_TIMEOUT)
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .disableContentCompression()
                .setUserAgent(USER_AGENT)
                .build();
        this.client.start();
        if (allowLoopback) {
            LOG.warn("Link previews may fetch loopback addresses (test setting)");
        }
    }

    /**
     * Fetches a URL, following redirects that pass the same checks.
     *
     * @param uri  absolute http(s) URL
     * @param kind expected content
     * @return the body
     * @throws FetchException if refused, unreachable, too slow, too large, or the wrong type
     */
    public Fetched fetch(URI uri, Kind kind) throws FetchException {
        Instant deadline = Instant.now().plus(TOTAL_TIMEOUT);
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            check(current);
            Duration left = Duration.between(Instant.now(), deadline);
            if (left.isNegative() || left.isZero()) {
                throw new FetchException("timed out");
            }
            CappedResponseConsumer.Response response = exchange(current, kind, left);
            int status = response.status();
            if (status >= 300 && status < 400) {
                if (response.location() == null) {
                    throw new FetchException("redirect without Location");
                }
                try {
                    current = current.resolve(response.location().strip());
                } catch (IllegalArgumentException e) {
                    throw new FetchException("bad redirect: " + response.location());
                }
                continue;
            }
            if (status != 200) {
                throw new FetchException("status " + status);
            }
            if (response.body() == null) {
                throw new FetchException("unexpected content type " + response.contentType());
            }
            return new Fetched(current, response.contentType(), response.body());
        }
        throw new FetchException("too many redirects");
    }

    /**
     * Refuses URLs that are not plain web addresses: other schemes, user info, non-default
     * ports, and non-public IP literals. Host names are checked when they are resolved.
     *
     * @param uri candidate URL
     * @throws FetchException if refused
     */
    void check(URI uri) throws FetchException {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new FetchException("scheme " + scheme);
        }
        if (uri.getRawUserInfo() != null) {
            throw new FetchException("user info in URL");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new FetchException("no host");
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        // Names are checked by PublicOnlyDnsResolver at connect time (one lookup, the one used).
        // IP literals need no DNS, so refuse non-public ones here with a clear reason.
        // URIs always bracket IPv6 literals, including mapped forms like [::ffff:10.0.0.1].
        boolean ipv6 = host.startsWith("[") && host.endsWith("]");
        if (ipv6 || InetAddressUtils.isIPv4(host)) {
            InetAddress address;
            try {
                // A literal parses without a DNS query.
                address = InetAddress.getByName(ipv6 ? host.substring(1, host.length() - 1) : host);
            } catch (UnknownHostException e) {
                throw new FetchException("bad address " + host);
            }
            if (!(allowLoopback && address.isLoopbackAddress()) && !isPublic(address)) {
                throw new FetchException("non-public address " + host);
            }
        }
        if (!defaultPort && !anyPort) {
            throw new FetchException("port " + port);
        }
    }

    /**
     * Whether an address is on the public internet.
     *
     * @param address resolved address
     * @return false for loopback, private, link-local, CGNAT, multicast, unique-local and reserved ranges
     */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return false; // fc00::/7 unique local
            }
            if (isPrefix(b, 0x20, 0x01, 0x0D, 0xB8)) {
                return false; // 2001:db8::/32 documentation
            }
            if (isPrefix(b, 0x00, 0x64, 0xFF, 0x9B)) {
                return false; // 64:ff9b::/96 NAT64 can map to private IPv4
            }
            boolean zeroTen = true;
            for (int i = 0; i < 10; i++) {
                zeroTen &= b[i] == 0;
            }
            if (zeroTen && (((b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) || (b[10] == 0 && b[11] == 0))) {
                // IPv4-mapped (::ffff:a.b.c.d) or IPv4-compatible (::a.b.c.d)
                return isPublicV4(new byte[]{b[12], b[13], b[14], b[15]});
            }
            return true;
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int a = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return !(a == 0                                   // 0.0.0.0/8
                || a == 10                                // 10/8
                || a == 127                               // loopback
                || (a == 100 && second >= 64 && second <= 127) // 100.64/10 CGNAT
                || (a == 169 && second == 254)            // link-local, cloud metadata
                || (a == 172 && second >= 16 && second <= 31)  // 172.16/12
                || (a == 192 && second == 168)            // 192.168/16
                || (a == 192 && second == 0 && (b[2] & 0xFF) == 0) // 192.0.0/24
                || (a == 198 && (second == 18 || second == 19))    // 198.18/15 benchmarking
                || a >= 224);                             // multicast, reserved, broadcast
    }

    private static boolean isPrefix(byte[] b, int... prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if ((b[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * One request with one deadline covering connect, headers and body. The consumer decides
     * as early as it can; the exchange is then cancelled so unread bytes are dropped.
     */
    private CappedResponseConsumer.Response exchange(URI uri, Kind kind, Duration left) throws FetchException {
        CappedResponseConsumer consumer = new CappedResponseConsumer(kind);
        Future<CappedResponseConsumer.Response> exchange = client.execute(
                AsyncRequestBuilder.get(uri)
                        .setHeader("Accept", kind == Kind.HTML ? "text/html,application/xhtml+xml" : "image/*")
                        .setHeader("Accept-Language", "en-US,en;q=0.8")
                        .build(),
                consumer, null);
        try {
            return consumer.result().get(left.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new FetchException("timed out");
        } catch (ExecutionException e) {
            throw new FetchException("unreachable: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted");
        } finally {
            exchange.cancel(true);
        }
    }

    /**
     * Closes the HTTP client and its pooled connections.
     */
    @PreDestroy
    void close() {
        try {
            client.close(CloseMode.GRACEFUL);
        } catch (RuntimeException e) {
            LOG.debugf("Closing link preview client: %s", e.getMessage());
        }
    }
}
