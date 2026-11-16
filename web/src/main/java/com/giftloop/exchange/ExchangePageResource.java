package com.giftloop.exchange;

import com.giftloop.security.RequiresOnboarding;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestForm;

import java.util.UUID;

/**
 * Secret Santa actions on an event page. Every action re-renders the {@code #exchange-panel}
 * fragment; refused actions show their reason in it.
 */
@Path("events/{id}/exchange")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class ExchangePageResource {

    private final JsonWebToken jwt;
    private final ExchangeService exchanges;

    /**
     * Creates the resource.
     *
     * @param jwt       caller's verified token
     * @param exchanges exchange access
     */
    public ExchangePageResource(JsonWebToken jwt, ExchangeService exchanges) {
        this.jwt = jwt;
        this.exchanges = exchanges;
    }

    /**
     * Templates under {@code templates/ExchangePageResource/}.
     */
    @CheckedTemplate
    public static class Templates {
        /**
         * Exchange panel fragment (also included by the event page).
         *
         * @param exchange the exchange
         * @return fragment
         */
        public static native TemplateInstance exchangePanel(ExchangeView exchange);
    }

    /**
     * Joins the exchange.
     *
     * @param id event id
     * @return panel
     */
    @POST
    @Path("join")
    @Operation(hidden = true)
    public TemplateInstance join(@PathParam("id") UUID id) {
        return act(id, () -> exchanges.join(jwt, id));
    }

    /**
     * Leaves the exchange.
     *
     * @param id event id
     * @return panel
     */
    @POST
    @Path("leave")
    @Operation(hidden = true)
    public TemplateInstance leave(@PathParam("id") UUID id) {
        return act(id, () -> exchanges.leave(jwt, id));
    }

    /**
     * Removes a participant (group owner/admin).
     *
     * @param id     event id
     * @param userId participant
     * @return panel
     * @throws ForbiddenException if the caller may not remove them
     */
    @POST
    @Path("participants/{userId}/remove")
    @Operation(hidden = true)
    public TemplateInstance removeParticipant(@PathParam("id") UUID id, @PathParam("userId") UUID userId) {
        return act(id, () -> exchanges.removeParticipant(jwt, id, userId));
    }

    /**
     * Adds an exclusion (group owner/admin).
     *
     * @param id    event id
     * @param userA one participant
     * @param userB the other participant
     * @return panel
     * @throws ForbiddenException if the caller may not manage exclusions
     */
    @POST
    @Path("exclusions")
    @Operation(hidden = true)
    public TemplateInstance addExclusion(@PathParam("id") UUID id, @RestForm String userA, @RestForm String userB) {
        UUID a = parse(userA);
        UUID b = parse(userB);
        return act(id, () -> {
            if (a == null || b == null) {
                throw new ExchangeRuleException("Pick two people.");
            }
            exchanges.addExclusion(jwt, id, a, b);
        });
    }

    /**
     * Removes an exclusion (group owner/admin).
     *
     * @param id    event id
     * @param userA one participant
     * @param userB the other participant
     * @return panel
     * @throws ForbiddenException if the caller may not manage exclusions
     */
    @POST
    @Path("exclusions/remove")
    @Operation(hidden = true)
    public TemplateInstance removeExclusion(@PathParam("id") UUID id, @RestForm UUID userA, @RestForm UUID userB) {
        return act(id, () -> exchanges.removeExclusion(jwt, id, userA, userB));
    }

    /**
     * Draws names (group owner/admin).
     *
     * @param id event id
     * @return panel
     * @throws ForbiddenException if the caller is not an owner/admin
     */
    @POST
    @Path("draw")
    @Operation(hidden = true)
    public TemplateInstance draw(@PathParam("id") UUID id) {
        return act(id, () -> exchanges.draw(jwt, id));
    }

    /**
     * Undoes the draw (group owner/admin).
     *
     * @param id event id
     * @return panel
     * @throws ForbiddenException if the caller is not an owner/admin
     */
    @POST
    @Path("reset")
    @Operation(hidden = true)
    public TemplateInstance reset(@PathParam("id") UUID id) {
        return act(id, () -> exchanges.reset(jwt, id));
    }

    /** Runs an action, then renders the panel with any rule message. */
    private TemplateInstance act(UUID id, Runnable action) {
        String error = null;
        try {
            action.run();
        } catch (ExchangeRuleException e) {
            error = e.getMessage();
        }
        ExchangeView view = exchanges.find(jwt, id).orElseThrow(NotFoundException::new);
        return Templates.exchangePanel(error == null ? view : view.withError(error));
    }

    private static UUID parse(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
