package com.giftloop.supabase;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import java.util.Map;

/**
 * Supabase Auth (GoTrue) endpoints used by GiftLoop. Prefer {@link SupabaseAuthService}.
 */
@RegisterRestClient(configKey = "supabase")
@RegisterProvider(SupabaseErrorMapper.class)
@ClientHeaderParam(name = "apikey", value = "${supabase.publishable-key}")
@Path("/auth/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface SupabaseAuthApi {

    /**
     * Creates a password account. Returns a session when email confirmation is off.
     *
     * @param body {@code email}, {@code password}
     * @return new session
     */
    @POST
    @Path("/signup")
    AuthSession signUp(Map<String, Object> body);

    /**
     * Exchanges credentials or a refresh token for a session.
     *
     * @param grantType {@code password} or {@code refresh_token}
     * @param body      grant-specific fields
     * @return session
     */
    @POST
    @Path("/token")
    AuthSession token(@QueryParam("grant_type") String grantType, Map<String, Object> body);

    /**
     * Non-blocking variant of {@link #token} used by the session refresh handler.
     *
     * @param grantType {@code refresh_token}
     * @param body      {@code refresh_token}
     * @return session
     */
    @POST
    @Path("/token")
    Uni<AuthSession> tokenAsync(@QueryParam("grant_type") String grantType, Map<String, Object> body);

    /**
     * Emails a one-time sign-in code.
     *
     * @param body {@code email}, {@code create_user}
     */
    @POST
    @Path("/otp")
    void sendOtp(Map<String, Object> body);

    /**
     * Emails a password recovery code. Succeeds silently for unknown emails.
     *
     * @param body {@code email}
     */
    @POST
    @Path("/recover")
    void recover(Map<String, Object> body);

    /**
     * Verifies an emailed code.
     *
     * @param body {@code email}, {@code token}, {@code type} ({@code email} or {@code recovery})
     * @return session
     */
    @POST
    @Path("/verify")
    AuthSession verify(Map<String, Object> body);

    /**
     * Updates the signed-in user (used to set a new password).
     *
     * @param bearer {@code Bearer <access token>}
     * @param body   fields to change, e.g. {@code password}
     */
    @PUT
    @Path("/user")
    void updateUser(@HeaderParam("Authorization") String bearer, Map<String, Object> body);

    /**
     * Revokes the session's refresh tokens.
     *
     * @param bearer {@code Bearer <access token>}
     */
    @POST
    @Path("/logout")
    void logout(@HeaderParam("Authorization") String bearer);
}
