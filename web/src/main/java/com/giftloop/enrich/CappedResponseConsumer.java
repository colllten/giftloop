package com.giftloop.enrich;

import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.EntityDetails;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.nio.AsyncResponseConsumer;
import org.apache.hc.core5.http.nio.CapacityChannel;
import org.apache.hc.core5.http.protocol.HttpContext;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Reads one link-preview response for {@link SafeFetcher}. The body is only collected for a
 * 200 of the expected type, and only up to the kind's cap: pages are cut there, images fail.
 * {@link #result()} completes as soon as the answer is known (headers for redirects and
 * errors, the cap for oversized bodies), and the caller then cancels the exchange, so the
 * rest of a large body is never read.
 */
final class CappedResponseConsumer implements AsyncResponseConsumer<CappedResponseConsumer.Response> {

    /**
     * What the fetcher needs from a response.
     *
     * @param status      HTTP status
     * @param location    {@code Location} header, or null
     * @param contentType {@code Content-Type} header, or empty
     * @param body        body (within the cap) for a 200 of the expected type, else null
     */
    record Response(int status, String location, String contentType, byte[] body) {
    }

    /** Body exceeded the cap for a kind that doesn't truncate. */
    static final class TooLargeException extends Exception {
        TooLargeException(int maxBytes) {
            super("body over " + maxBytes + " bytes");
        }
    }

    private final SafeFetcher.Kind kind;
    private final CompletableFuture<Response> result = new CompletableFuture<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private int status;
    private String location;
    private String contentType = "";

    /**
     * Creates the consumer.
     *
     * @param kind expected content (types and cap)
     */
    CappedResponseConsumer(SafeFetcher.Kind kind) {
        this.kind = kind;
    }

    /**
     * Completes once the response is decided.
     *
     * @return the response, or a failure ({@link TooLargeException}, I/O, DNS refusal)
     */
    CompletableFuture<Response> result() {
        return result;
    }

    /**
     * Reads status and headers; finishes right away unless the body is wanted.
     *
     * @param response      response head
     * @param entityDetails body details, or null when there is no body
     * @param context       exchange context
     * @param callback      unused; {@link #result()} carries the outcome
     */
    @Override
    public void consumeResponse(HttpResponse response, EntityDetails entityDetails, HttpContext context,
                                FutureCallback<Response> callback) {
        status = response.getCode();
        Header loc = response.getFirstHeader("Location");
        location = loc == null ? null : loc.getValue();
        Header type = response.getFirstHeader("Content-Type");
        contentType = type == null ? "" : type.getValue();
        if (status != 200 || !kind.accepts(contentType)) {
            result.complete(new Response(status, location, contentType, null));
        } else if (entityDetails == null) {
            result.complete(new Response(status, location, contentType, new byte[0]));
        }
    }

    /**
     * Ignored (1xx responses).
     *
     * @param response informational response
     * @param context  exchange context
     */
    @Override
    public void informationResponse(HttpResponse response, HttpContext context) {
        // nothing to do
    }

    /**
     * Lets data flow; the cap is enforced in {@link #consume}.
     *
     * @param channel flow-control channel
     */
    @Override
    public void updateCapacity(CapacityChannel channel) throws java.io.IOException {
        channel.update(Integer.MAX_VALUE);
    }

    /**
     * Appends body bytes up to the cap; anything after the result is decided is discarded.
     *
     * @param src incoming bytes
     */
    @Override
    public void consume(ByteBuffer src) {
        if (result.isDone()) {
            src.position(src.limit());
            return;
        }
        int room = kind.maxBytes() - out.size();
        if (src.remaining() > room) {
            if (kind.truncates()) {
                byte[] head = new byte[room];
                src.get(head);
                out.writeBytes(head);
                result.complete(new Response(status, location, contentType, out.toByteArray()));
            } else {
                result.completeExceptionally(new TooLargeException(kind.maxBytes()));
            }
            src.position(src.limit());
            return;
        }
        byte[] chunk = new byte[src.remaining()];
        src.get(chunk);
        out.writeBytes(chunk);
    }

    /**
     * Body finished within the cap.
     *
     * @param trailers trailing headers (ignored)
     */
    @Override
    public void streamEnd(List<? extends Header> trailers) {
        result.complete(new Response(status, location, contentType, out.toByteArray()));
    }

    /**
     * Exchange failed (connect, DNS refusal, reset).
     *
     * @param cause the failure
     */
    @Override
    public void failed(Exception cause) {
        result.completeExceptionally(cause);
    }

    /**
     * Nothing held beyond the buffer.
     */
    @Override
    public void releaseResources() {
        // nothing to release
    }
}
