package com.giftloop.enrich;

import com.giftloop.image.Images;
import com.giftloop.wishlist.GiftImageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a product link into gift details and a normalized photo, for auto-fill.
 * Never throws for bad links or unfriendly sites: the result says what went wrong in
 * words for the user, and they fill the form in by hand.
 *
 * <p>Callers rate limit first with {@link AutofillQuota}, since every preview makes outbound
 * requests on the user's behalf.
 */
@ApplicationScoped
public class LinkPreviewService {

    private static final Pattern CHARSET = Pattern.compile("charset=\"?([\\w.:-]+)", Pattern.CASE_INSENSITIVE);
    private static final Logger LOG = Logger.getLogger(LinkPreviewService.class);

    private final SafeFetcher fetcher;

    /**
     * Result of an auto-fill attempt.
     *
     * @param preview details found (null on failure)
     * @param image   normalized JPEG photo, or null
     * @param problem user-facing message when nothing could be read, else null
     * @param outcome what happened, for metrics
     */
    public record Result(LinkPreview preview, byte[] image, String problem, Outcome outcome) {

        static Result failed(String problem, Outcome outcome) {
            return new Result(null, null, problem, outcome);
        }
    }

    /** How an auto-fill attempt ended (metric tag; see {@code AppMetrics#autofillDone}). */
    public enum Outcome { INVALID_LINK, FETCH_FAILED, NO_DETAILS, FILLED }

    /**
     * Creates the service.
     *
     * @param fetcher SSRF-safe HTTP fetcher
     */
    @Inject
    public LinkPreviewService(SafeFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Reads product details and photo from a link.
     *
     * @param url absolute http(s) URL
     * @return details, or a message for the user
     */
    public Result preview(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException e) {
            return Result.failed("That doesn't look like a link. Please check it and try again.", Outcome.INVALID_LINK);
        }
        SafeFetcher.Fetched page;
        try {
            page = fetcher.fetch(uri, SafeFetcher.Kind.HTML);
        } catch (SafeFetcher.FetchException e) {
            LOG.infof("Link preview failed for %s: %s", uri.getHost(), e.getMessage());
            return Result.failed("We couldn't read details from that site. Please fill them in below.",
                    Outcome.FETCH_FAILED);
        }
        LinkPreview preview = ProductMetadataParser.parse(decode(page), page.uri());
        if (preview.isEmpty()) {
            LOG.infof("Link preview found nothing on %s", uri.getHost());
            return new Result(preview, null, "We couldn't find product details on that page. Please fill them in below.",
                    Outcome.NO_DETAILS);
        }
        return new Result(preview, photo(preview.imageUrl()), null, Outcome.FILLED);
    }

    /** Fetches and normalizes the product photo; null if missing or unusable. */
    private byte[] photo(URI imageUrl) {
        if (imageUrl == null) {
            return null;
        }
        try {
            return GiftImageService.normalize(fetcher.fetch(imageUrl, SafeFetcher.Kind.IMAGE).body());
        } catch (SafeFetcher.FetchException | Images.InvalidImageException e) {
            LOG.infof("Link preview photo skipped (%s): %s", imageUrl.getHost(), e.getMessage());
            return null;
        }
    }

    /** Decodes with the Content-Type charset, defaulting to UTF-8 (jsoup re-reads meta charset poorly from strings). */
    private static String decode(SafeFetcher.Fetched page) {
        Matcher m = CHARSET.matcher(page.contentType());
        Charset charset = StandardCharsets.UTF_8;
        if (m.find()) {
            try {
                charset = Charset.forName(m.group(1));
            } catch (IllegalArgumentException e) {
                // unknown charset: keep UTF-8
            }
        }
        return new String(page.body(), charset);
    }
}
