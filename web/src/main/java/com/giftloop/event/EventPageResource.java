package com.giftloop.event;

import com.giftloop.exchange.ExchangeService;
import com.giftloop.exchange.ExchangeView;
import com.giftloop.group.GroupDetail;
import com.giftloop.group.GroupService;
import com.giftloop.security.RequiresOnboarding;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.ForbiddenException;
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
 * Event pages: create an event in a group, the event page listing members' wishlists, and
 * editing or deleting an event (group owner/admin).
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class EventPageResource {

    private final JsonWebToken jwt;
    private final EventService events;
    private final GroupService groups;
    private final ExchangeService exchanges;

    /**
     * Creates the resource.
     *
     * @param jwt       caller's verified token
     * @param events    event access
     * @param groups    group access (for the new-event page heading)
     * @param exchanges Secret Santa access (for exchange events)
     */
    public EventPageResource(JsonWebToken jwt, EventService events, GroupService groups, ExchangeService exchanges) {
        this.jwt = jwt;
        this.events = events;
        this.groups = groups;
        this.exchanges = exchanges;
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
         * Edit-event page.
         *
         * @param view form data
         * @return page
         */
        static native TemplateInstance edit(EventFormView view);

        /**
         * New- or edit-event form fragment.
         *
         * @param view form data
         * @return fragment
         */
        static native TemplateInstance eventForm(EventFormView view);

        /**
         * Event page.
         *
         * @param event    the event
         * @param exchange its Secret Santa exchange, or null for a standard event
         * @return page
         */
        static native TemplateInstance event(EventDetail event, ExchangeView exchange);
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
        return Templates.create(EventFormView.blank(groupId, group.name()));
    }

    /**
     * Creates an event and opens it.
     *
     * @param groupId group id
     * @param name    event name
     * @param endDate optional last day ({@code yyyy-MM-dd})
     * @param type    {@code standard} (default) or {@code exchange}
     * @param budget  optional exchange spending limit
     * @param headers request headers
     * @return redirect to the event, or the form with errors
     */
    @POST
    @Path("groups/{id}/events")
    @Operation(hidden = true)
    public Response create(@PathParam("id") UUID groupId, @RestForm String name, @RestForm String endDate,
                           @RestForm String type, @RestForm String budget, HttpHeaders headers) {
        EventForm form = EventForm.of(name, endDate, type, budget);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return Response.ok(Templates.eventForm(new EventFormView(groupId, null, null, name, endDate,
                    form.type(), budget, false, errors))).build();
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
        EventDetail event = events.find(jwt, id).orElseThrow(NotFoundException::new);
        ExchangeView exchange = event.type().isExchange() ? exchanges.find(jwt, id).orElse(null) : null;
        return Templates.event(event, exchange);
    }

    /**
     * Edit-event form (group owner/admin).
     *
     * @param id event id
     * @return page
     * @throws NotFoundException  if missing or the caller is not a member of its group
     * @throws ForbiddenException if the caller is a member but not an owner/admin
     */
    @GET
    @Path("events/{id}/edit")
    @Operation(hidden = true)
    public TemplateInstance editPage(@PathParam("id") UUID id) {
        EventDetail event = events.find(jwt, id).orElseThrow(NotFoundException::new);
        if (!event.canManage()) {
            throw new ForbiddenException();
        }
        String endDate = event.endDate() == null ? null : event.endDate().toString();
        String budget = event.budget() == null ? null : event.budget().toPlainString();
        return Templates.edit(new EventFormView(event.groupId(), event.groupName(), id, event.name(), endDate,
                event.type(), budget, event.drawn(), Map.of()));
    }

    /**
     * Saves an event's name, end date, type and budget (group owner/admin), then opens it.
     *
     * @param id      event id
     * @param name    event name
     * @param endDate optional last day ({@code yyyy-MM-dd})
     * @param type    {@code standard} or {@code exchange}
     * @param budget  optional exchange spending limit
     * @param headers request headers
     * @return redirect to the event, or the form with errors
     * @throws ForbiddenException if the event is missing or the caller may not edit it
     */
    @POST
    @Path("events/{id}")
    @Operation(hidden = true)
    public Response update(@PathParam("id") UUID id, @RestForm String name, @RestForm String endDate,
                           @RestForm String type, @RestForm String budget, HttpHeaders headers) {
        EventForm form = EventForm.of(name, endDate, type, budget);
        Map<String, String> errors = form.validate();
        if (errors.isEmpty()) {
            try {
                events.update(jwt, id, form);
                return Redirects.to(headers, "/events/" + id).build();
            } catch (EventTypeLockedException e) {
                errors = Map.of("type", "Names are drawn, so the type can't change. Undo the draw first.");
            }
        }
        // The type radios are re-enabled here; the database still refuses a change after the draw.
        return Response.ok(Templates.eventForm(new EventFormView(null, null, id, name, endDate, form.type(), budget,
                false, errors))).build();
    }

    /**
     * Deletes an event with its wishlists, gifts and claims (group owner/admin).
     *
     * @param id      event id
     * @param headers request headers
     * @return redirect to the event's group
     * @throws ForbiddenException if the event is missing or the caller may not delete it
     */
    @POST
    @Path("events/{id}/delete")
    @Operation(hidden = true)
    public Response delete(@PathParam("id") UUID id, HttpHeaders headers) {
        UUID groupId = events.delete(jwt, id);
        return Redirects.to(headers, "/groups/" + groupId).build();
    }
}
