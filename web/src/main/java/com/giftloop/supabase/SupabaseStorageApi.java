package com.giftloop.supabase;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Supabase Storage endpoints for the private {@code avatars} bucket. Every call
 * passes the user's token so Storage RLS policies apply (ADR 001).
 */
@RegisterRestClient(configKey = "supabase")
@RegisterProvider(SupabaseErrorMapper.class)
@ClientHeaderParam(name = "apikey", value = "${supabase.publishable-key}")
@Path("/storage/v1/object")
public interface SupabaseStorageApi {

    /**
     * Uploads a JPEG to {@code avatars/<owner>/<file>}.
     *
     * @param bearer {@code Bearer <access token>}
     * @param owner  owner's user id (first path segment)
     * @param file   file name
     * @param bytes  JPEG bytes
     */
    @POST
    @Path("/avatars/{owner}/{file}")
    @Consumes("image/jpeg")
    void upload(@HeaderParam("Authorization") String bearer, @PathParam("owner") String owner,
                @PathParam("file") String file, byte[] bytes);

    /**
     * Downloads an object the caller is allowed to read.
     *
     * @param bearer {@code Bearer <access token>}
     * @param owner  owner's user id
     * @param file   file name
     * @return object bytes
     */
    @GET
    @Path("/authenticated/avatars/{owner}/{file}")
    byte[] download(@HeaderParam("Authorization") String bearer, @PathParam("owner") String owner,
                    @PathParam("file") String file);

    /**
     * Deletes an object the caller owns.
     *
     * @param bearer {@code Bearer <access token>}
     * @param owner  owner's user id
     * @param file   file name
     */
    @DELETE
    @Path("/avatars/{owner}/{file}")
    void delete(@HeaderParam("Authorization") String bearer, @PathParam("owner") String owner,
                @PathParam("file") String file);
}
