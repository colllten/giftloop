package com.giftloop.event;

import com.giftloop.group.GroupDetail;
import com.giftloop.group.GroupService;
import com.giftloop.security.RequiresOnboarding;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestForm;

import java.util.Map;
import java.util.UUID;

/**
 * Event pages: create an event in a group, and the event page listing members' wishlists.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class EventPageResource {

    private final JsonWebToken jwt;
    private final EventService events;
    private final GroupService groups;

    /**
     * Creates the resource.
     *
     * @param jwt    caller's verified token
     * @param events event access
     * @param groups group access (for the new-event page heading)
     */
    public EventPageResource(JsonWebToken jwt, EventService events, GroupService groups) {
        this.jwt = jwt;
        this.events = events;
        this.groups = groups;
    }

    /**
     * Templates under {@code templates/EventPageResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * New-event page.
         *
         * @param view form data
         * @return page
         */
        static native TemplateInstance create(EventFormView view);

        /**
         * New-event form fragment.
         *
         * @param view form data
         * @return fragment
         */
        static native TemplateInstance createForm(EventFormView view);

        /**
         * Event page.
         *
         * @param event the event
         * @return page
         */
        static native TemplateInstance event(EventDetail event);
    }

    /**
     * New-event form.
     *
     * @param groupId group id
     * @return page
     * @throws NotFoundException if the group is missing or the caller is not a member
     */
    @GET
    @Path("groups/{id}/events/new")
    @Operation(hidden = true)
    public TemplateInstance createPage(@PathParam("id") UUID groupId) {
        GroupDetail group = groups.find(jwt, groupId).orElseThrow(NotFoundException::new);
        return Templates.create(new EventFormView(groupId, group.name(), null, null, Map.of()));
    }

    /**
     * Creates an event and opens it.
     *
     * @param groupId group id
     * @param name    event name
     * @param endDate optional last day ({@code yyyy-MM-dd})
     * @param headers request headers
     * @return redirect to the event, or the form with errors
     */
    @POST
    @Path("groups/{id}/events")
    @Operation(hidden = true)
    public Response create(@PathParam("id") UUID groupId, @RestForm String name, @RestForm String endDate,
                           HttpHeaders headers) {
        EventForm form = EventForm.of(name, endDate);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return Response.ok(Templates.createForm(new EventFormView(groupId, null, name, endDate, errors))).build();
        }
        UUID id = events.create(jwt, groupId, form);
        return Redirects.to(headers, "/events/" + id).build();
    }

    /**
     * Event page.
     *
     * @param id event id
     * @return page
     * @throws NotFoundException if missing or the caller is not a member of its group
     */
    @GET
    @Path("events/{id}")
    @Operation(hidden = true)
    public TemplateInstance eventPage(@PathParam("id") UUID id) {
        return Templates.event(events.find(jwt, id).orElseThrow(NotFoundException::new));
    }
}
