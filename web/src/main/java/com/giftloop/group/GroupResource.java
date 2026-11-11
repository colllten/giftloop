package com.giftloop.group;

import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

import java.util.List;
import java.util.UUID;

/**
 * Read-only group endpoints. Writes are HTML-only until JSON writes have a CSRF strategy (ADR 003).
 */
@Path("/api/groups")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
@SecurityRequirement(name = "supabase")
public class GroupResource {

    private final JsonWebToken jwt;
    private final GroupService groups;

    /**
     * Creates the resource.
     *
     * @param jwt    verified token of the caller
     * @param groups group access (RLS-scoped)
     */
    public GroupResource(JsonWebToken jwt, GroupService groups) {
        this.jwt = jwt;
        this.groups = groups;
    }

    /**
     * The caller's groups.
     *
     * @return groups by name
     */
    @GET
    @Operation(summary = "List the signed-in user's groups")
    public List<GroupSummary> list() {
        return groups.listMine(jwt);
    }

    /**
     * One of the caller's groups with its members.
     *
     * @param id group id
     * @return the group
     * @throws NotFoundException if missing or the caller is not a member
     */
    @GET
    @Path("{id}")
    @Operation(summary = "Get a group the signed-in user belongs to")
    public GroupDto get(@PathParam("id") UUID id) {
        return groups.find(jwt, id).map(GroupDto::of).orElseThrow(NotFoundException::new);
    }
}
