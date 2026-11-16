package com.giftloop.legal;

import com.giftloop.security.RlsContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Current legal document version and users' acceptance of it (stored on {@code "Profile"}).
 */
@ApplicationScoped
public class LegalService {

    private static final Logger LOG = Logger.getLogger(LegalService.class);

    private final RlsContext rls;
    private final LegalInfo info;

    /**
     * Creates the service.
     *
     * @param rls          user-scoped DB access
     * @param version      current document version (ISO date); bumping it makes everyone re-accept
     * @param contactEmail address for privacy and legal requests
     */
    public LegalService(RlsContext rls,
                        @ConfigProperty(name = "giftloop.legal.version") String version,
                        @ConfigProperty(name = "giftloop.legal.contact-email") String contactEmail) {
        this.rls = rls;
        this.info = new LegalInfo(version, contactEmail);
    }

    /**
     * Values for the legal pages.
     *
     * @return version and contact
     */
    public LegalInfo info() {
        return info;
    }

    /**
     * Whether the caller has accepted the current version.
     *
     * @param jwt caller's verified token
     * @return true if their recorded version is current
     */
    public boolean hasAcceptedCurrent(JsonWebToken jwt) {
        String accepted = rls.inUserTransaction(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement(
                    "select terms_version from \"Profile\" where id = auth.uid()");
                 ResultSet r = s.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        });
        return info.version().equals(accepted);
    }

    /**
     * Records that the caller accepted the current Terms and Privacy Policy, now.
     *
     * @param jwt caller's verified token
     */
    public void recordAcceptance(JsonWebToken jwt) {
        rls.inUserTransaction(jwt, c -> {
            try (PreparedStatement s = c.prepareStatement("select private.accept_terms(?)")) {
                s.setString(1, info.version());
                s.execute();
                return null;
            }
        });
        LOG.infof("Legal documents %s accepted by %s", info.version(), jwt.getSubject());
    }
}
