package com.giftloop.supabase;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Supabase Storage endpoints for the private {@code avatars} and {@code gift-images}
 * buckets. Every call passes the user's token so Storage RLS policies apply (ADR 001).
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

    /**
     * Uploads a JPEG to {@code gift-images/<path>}, where path is
     * {@code <owner>/<gift>/<file>}.
     *
     * @param bearer {@code Bearer <access token>}
     * @param owner  wishlist owner's user id
     * @param gift   gift id
     * @param file   file name
     * @param bytes  JPEG bytes
     */
    @POST
    @Path("/gift-images/{owner}/{gift}/{file}")
    @Consumes("image/jpeg")
    void uploadGiftImage(@HeaderParam("Authorization") String bearer, @PathParam("owner") String owner,
                         @PathParam("gift") String gift, @PathParam("file") String file, byte[] bytes);

    /**
     * Downloads a gift image the caller is allowed to read.
     *
     * @param bearer {@code Bearer <access token>}
     * @param owner  wishlist owner's user id
     * @param gift   gift id
     * @param file   file name
     * @return object bytes
     */
    @GET
    @Path("/authenticated/gift-images/{owner}/{gift}/{file}")
    byte[] downloadGiftImage(@HeaderParam("Authorization") String bearer, @PathParam("owner") String owner,
                             @PathParam("gift") String gift, @PathParam("file") String file);

    /**
     * Deletes a gift image in the caller's folder.
     *
     * @param bearer {@code Bearer <access token>}
     * @param owner  wishlist owner's user id
     * @param gift   gift id
     * @param file   file name
     */
    @DELETE
    @Path("/gift-images/{owner}/{gift}/{file}")
    void deleteGiftImage(@HeaderParam("Authorization") String bearer, @PathParam("owner") String owner,
                         @PathParam("gift") String gift, @PathParam("file") String file);

    /**
     * Copies an object within a bucket (server side, no download).
     *
     * @param bearer  {@code Bearer <access token>}
     * @param request bucket, source and destination keys
     */
    @POST
    @Path("/copy")
    @Consumes(MediaType.APPLICATION_JSON)
    void copy(@HeaderParam("Authorization") String bearer, CopyRequest request);

    /**
     * Body of {@link #copy}.
     *
     * @param bucketId       bucket
     * @param sourceKey      existing object path
     * @param destinationKey new object path
     */
    record CopyRequest(String bucketId, String sourceKey, String destinationKey) {
    }
}
