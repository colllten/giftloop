package com.giftloop.enrich;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches user-supplied URLs (product pages and their images) without letting them reach
 * the server's own network (SSRF).
 *
 * <ul>
 *   <li>http/https on the default ports only; no user info in the URL.</li>
 *   <li>Every resolved address must be public: no loopback, private, link-local (cloud
 *       metadata), CGNAT, multicast, unique-local or reserved ranges.</li>
 *   <li>Redirects are followed by hand (at most {@value #MAX_REDIRECTS}) and each hop is
 *       re-checked.</li>
 *   <li>Short timeouts, a size cap per kind, and a content-type check.</li>
 * </ul>
 *
 * <p>Residual risk: the HTTP client resolves the host again after the check, so a DNS
 * rebinding attacker could swap in a private address between the two lookups. See
 * {@code docs/SECURITY.md}.
 */
@ApplicationScoped
public class SafeFetcher {

    /** Most redirects followed. */
    static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(6);
    private static final String USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0 Safari/537.36 GiftLoopLinkPreview/1.0";
    private static final Logger LOG = Logger.getLogger(SafeFetcher.class);

    /**
     * What is being fetched: decides the accepted content types and size cap.
     */
    public enum Kind {
        /** A product page. */
        HTML(1_536 * 1024),
        /** A product photo. */
        IMAGE(5 * 1024 * 1024);

        private final int maxBytes;

        Kind(int maxBytes) {
            this.maxBytes = maxBytes;
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
    private final HttpClient client;

    /**
     * Creates the fetcher.
     *
     * @param allowLoopback {@code giftloop.link-preview.allow-loopback}; never set outside tests
     */
    public SafeFetcher(@ConfigProperty(name = "giftloop.link-preview.allow-loopback", defaultValue = "false")
                       boolean allowLoopback) {
        this.allowLoopback = allowLoopback;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
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
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(left)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", kind == Kind.HTML ? "text/html,application/xhtml+xml" : "image/*")
                    .header("Accept-Language", "en-US,en;q=0.8")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = send(request, kind, left);
            int status = response.statusCode();
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new FetchException("redirect without Location"));
                try {
                    current = current.resolve(location.strip());
                } catch (IllegalArgumentException e) {
                    throw new FetchException("bad redirect: " + location);
                }
                continue;
            }
            if (status != 200) {
                throw new FetchException("status " + status);
            }
            String type = response.headers().firstValue("Content-Type").orElse("");
            if (response.body() == null) {
                throw new FetchException("unexpected content type " + type);
            }
            return new Fetched(current, type, response.body());
        }
        throw new FetchException("too many redirects");
    }

    /**
     * Refuses URLs that are not plain web addresses on public hosts.
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
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new FetchException("unknown host " + host);
        }
        for (InetAddress address : addresses) {
            boolean loopbackAllowed = allowLoopback && address.isLoopbackAddress();
            if (!loopbackAllowed && !isPublic(address)) {
                throw new FetchException("non-public address for " + host);
            }
        }
        if (!defaultPort && !(allowLoopback && addresses.length > 0 && addresses[0].isLoopbackAddress())) {
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
     * Sends with one deadline covering headers and body. Bodies are only read for a 200 of
     * the expected type, and reading stops at the kind's cap.
     */
    private HttpResponse<byte[]> send(HttpRequest request, Kind kind, Duration left) throws FetchException {
        HttpResponse.BodyHandler<byte[]> handler = info -> {
            String type = info.headers().firstValue("Content-Type").orElse("");
            return info.statusCode() == 200 && kind.accepts(type)
                    ? new CappedBody(kind.maxBytes())
                    : HttpResponse.BodySubscribers.replacing(null);
        };
        CompletableFuture<HttpResponse<byte[]>> future = client.sendAsync(request, handler);
        try {
            return future.get(left.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new FetchException("timed out");
        } catch (ExecutionException e) {
            throw new FetchException("failed: " + e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FetchException("interrupted");
        }
    }

    /** Collects a body, cancelling once it passes {@code maxBytes}. */
    private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maxBytes;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBody(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                if (out.size() + item.remaining() > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new IllegalStateException("body over " + maxBytes + " bytes"));
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                out.writeBytes(chunk);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}
