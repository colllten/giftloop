package com.giftloop.group;

import com.giftloop.security.RequiresOnboarding;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.resteasy.reactive.RestForm;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Group pages: create, join by code, and the group page with member management.
 * Mutations on the group page return the refreshed {@code groupBody} fragment.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
@Authenticated
@RequiresOnboarding
public class GroupPageResource {

    static final String UNKNOWN_CODE = "We couldn't find a group with that code. Check it with the person who shared it.";
    static final String MALFORMED_CODE = "Join codes have 8 letters and numbers, like ABCD-EFGH.";

    private final JsonWebToken jwt;
    private final GroupService groups;

    /**
     * Creates the resource.
     *
     * @param jwt    caller's verified token
     * @param groups group access
     */
    public GroupPageResource(JsonWebToken jwt, GroupService groups) {
        this.jwt = jwt;
        this.groups = groups;
    }

    /**
     * Templates under {@code templates/GroupPageResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Create-group page.
         *
         * @param name  name field value
         * @param error name error, or null
         * @return page
         */
        static native TemplateInstance create(String name, String error);

        /**
         * Create-group form fragment.
         *
         * @param name  name field value
         * @param error name error, or null
         * @return fragment
         */
        static native TemplateInstance createForm(String name, String error);

        /**
         * Join page.
         *
         * @param code  code field value
         * @param error code error, or null
         * @return page
         */
        static native TemplateInstance join(String code, String error);

        /**
         * Join form fragment.
         *
         * @param code  code field value
         * @param error code error, or null
         * @return fragment
         */
        static native TemplateInstance joinForm(String code, String error);

        /**
         * Group page.
         *
         * @param view body data
         * @return page
         */
        static native TemplateInstance group(GroupBodyView view);

        /**
         * Group page body fragment (everything below the top bar).
         *
         * @param view body data
         * @return fragment
         */
        static native TemplateInstance groupBody(GroupBodyView view);
    }

    /**
     * Create-group form.
     *
     * @return page
     */
    @GET
    @Path("groups/new")
    @Operation(hidden = true)
    public TemplateInstance createPage() {
        return Templates.create(null, null);
    }

    /**
     * Creates a group and opens it.
     *
     * @param name    group name
     * @param headers request headers
     * @return redirect to the group, or the form with an error
     */
    @POST
    @Path("groups")
    @Operation(hidden = true)
    public Response create(@RestForm String name, HttpHeaders headers) {
        GroupForm form = GroupForm.of(name);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return Response.ok(Templates.createForm(name, errors.get("name"))).build();
        }
        UUID id = groups.create(jwt, form);
        return Redirects.to(headers, groupPath(id)).build();
    }

    /**
     * Join-by-code form.
     *
     * @param code optional code to prefill
     * @return page
     */
    @GET
    @Path("join")
    @Operation(hidden = true)
    public TemplateInstance joinPage(@QueryParam("code") String code) {
        return Templates.join(code, null);
    }

    /**
     * Joins the group with this code and opens it.
     *
     * @param code    join code
     * @param headers request headers
     * @return redirect to the group, or the form with an error
     */
    @POST
    @Path("join")
    @Operation(hidden = true)
    public Response join(@RestForm String code, HttpHeaders headers) {
        if (!JoinCode.isWellFormed(JoinCode.normalize(code))) {
            return Response.ok(Templates.joinForm(code, MALFORMED_CODE)).build();
        }
        Optional<UUID> id;
        try {
            id = groups.join(jwt, code);
        } catch (JoinRateLimitedException e) {
            return Response.ok(Templates.joinForm(code, rateLimitedMessage(e.retryAfter()))).build();
        }
        if (id.isEmpty()) {
            return Response.ok(Templates.joinForm(code, UNKNOWN_CODE)).build();
        }
        return Redirects.to(headers, groupPath(id.get())).build();
    }

    /**
     * Join form error while rate limited, with the wait rounded up to whole minutes.
     *
     * @param retryAfter time until the user may try again
     * @return e.g. "Too many incorrect codes. Try again in 12 minutes."
     */
    static String rateLimitedMessage(Duration retryAfter) {
        long minutes = Math.max(1, (retryAfter.toSeconds() + 59) / 60);
        return "Too many incorrect codes. Try again in " + minutes + (minutes == 1 ? " minute." : " minutes.");
    }

    /**
     * Group page.
     *
     * @param id group id
     * @return page
     * @throws NotFoundException if missing or the caller is not a member
     */
    @GET
    @Path("groups/{id}")
    @Operation(hidden = true)
    public TemplateInstance groupPage(@PathParam("id") UUID id) {
        return Templates.group(GroupBodyView.of(load(id)));
    }

    /**
     * Renames the group (owner/admin).
     *
     * @param id   group id
     * @param name new name
     * @return refreshed body, with an error if the name is invalid
     */
    @POST
    @Path("groups/{id}/rename")
    @Operation(hidden = true)
    public TemplateInstance rename(@PathParam("id") UUID id, @RestForm String name) {
        GroupForm form = GroupForm.of(name);
        Map<String, String> errors = form.validate();
        if (!errors.isEmpty()) {
            return Templates.groupBody(new GroupBodyView(load(id), name, errors, null));
        }
        groups.rename(jwt, id, form);
        return Templates.groupBody(GroupBodyView.withNotice(load(id), "Group renamed."));
    }

    /**
     * Replaces the join code (owner/admin).
     *
     * @param id group id
     * @return refreshed body
     */
    @POST
    @Path("groups/{id}/code")
    @Operation(hidden = true)
    public TemplateInstance regenerateCode(@PathParam("id") UUID id) {
        groups.regenerateJoinCode(jwt, id);
        return Templates.groupBody(GroupBodyView.withNotice(load(id), "New code created. The old code no longer works."));
    }

    /**
     * Makes a member an admin or a plain member (owner).
     *
     * @param id     group id
     * @param userId member
     * @param role   {@code admin} or {@code member}
     * @return refreshed body
     */
    @POST
    @Path("groups/{id}/members/{userId}/role")
    @Operation(hidden = true)
    public TemplateInstance setRole(@PathParam("id") UUID id, @PathParam("userId") UUID userId,
                                    @RestForm String role) {
        groups.setRole(jwt, id, userId, parseRole(role));
        return Templates.groupBody(GroupBodyView.withNotice(load(id), "Role updated."));
    }

    /**
     * Removes a member (owner: anyone else; admin: plain members).
     *
     * @param id     group id
     * @param userId member to remove
     * @return refreshed body
     */
    @POST
    @Path("groups/{id}/members/{userId}/remove")
    @Operation(hidden = true)
    public TemplateInstance removeMember(@PathParam("id") UUID id, @PathParam("userId") UUID userId) {
        groups.removeMember(jwt, id, userId);
        return Templates.groupBody(GroupBodyView.withNotice(load(id), "Member removed."));
    }

    /**
     * Hands ownership to another member (owner).
     *
     * @param id     group id
     * @param userId new owner
     * @return refreshed body
     */
    @POST
    @Path("groups/{id}/transfer")
    @Operation(hidden = true)
    public TemplateInstance transfer(@PathParam("id") UUID id, @RestForm UUID userId) {
        if (userId == null) {
            throw new BadRequestException("userId is required");
        }
        groups.transferOwnership(jwt, id, userId);
        return Templates.groupBody(GroupBodyView.withNotice(load(id), "Ownership transferred. You're now an admin."));
    }

    /**
     * Leaves the group (not allowed for the owner).
     *
     * @param id      group id
     * @param headers request headers
     * @return redirect home
     */
    @POST
    @Path("groups/{id}/leave")
    @Operation(hidden = true)
    public Response leave(@PathParam("id") UUID id, HttpHeaders headers) {
        groups.removeMember(jwt, id, UUID.fromString(jwt.getSubject()));
        return Redirects.to(headers, "/").build();
    }

    /**
     * Deletes the group (owner).
     *
     * @param id      group id
     * @param headers request headers
     * @return redirect home
     */
    @POST
    @Path("groups/{id}/delete")
    @Operation(hidden = true)
    public Response delete(@PathParam("id") UUID id, HttpHeaders headers) {
        groups.delete(jwt, id);
        return Redirects.to(headers, "/").build();
    }

    private GroupDetail load(UUID id) {
        return groups.find(jwt, id).orElseThrow(NotFoundException::new);
    }

    private static String groupPath(UUID id) {
        return "/groups/" + id;
    }

    private static GroupRole parseRole(String role) {
        try {
            return GroupRole.fromDb(role == null ? "" : role);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown role");
        }
    }
}
