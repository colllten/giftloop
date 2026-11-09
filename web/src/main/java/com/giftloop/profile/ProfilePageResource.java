package com.giftloop.profile;

import com.giftloop.security.RequiresOnboarding;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Onboarding (first-time profile setup) and profile editing pages.
 */
@Path("/")
@Produces(MediaType.TEXT_HTML)
@Authenticated
public class ProfilePageResource {

    private static final Logger LOG = Logger.getLogger(ProfilePageResource.class);

    private final JsonWebToken jwt;
    private final ProfileService profiles;
    private final AvatarService avatars;

    /**
     * Creates the resource.
     *
     * @param jwt      caller's verified token
     * @param profiles profile access
     * @param avatars  avatar storage
     */
    public ProfilePageResource(JsonWebToken jwt, ProfileService profiles, AvatarService avatars) {
        this.jwt = jwt;
        this.profiles = profiles;
        this.avatars = avatars;
    }

    /**
     * Templates under {@code templates/ProfilePageResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Onboarding page.
         *
         * @param view form data
         * @return page
         */
        static native TemplateInstance onboarding(ProfileFormView view);

        /**
         * Profile edit page.
         *
         * @param view form data
         * @return page
         */
        static native TemplateInstance profile(ProfileFormView view);

        /**
         * Shared form fragment.
         *
         * @param view form data
         * @return fragment
         */
        static native TemplateInstance profileForm(ProfileFormView view);

        /**
         * Username availability message.
         *
         * @param message text to show
         * @param ok      true if available
         * @return fragment
         */
        static native TemplateInstance usernameStatus(String message, boolean ok);
    }

    /**
     * Onboarding form. Already-onboarded users continue to {@code next}.
     *
     * @param next    where to go afterwards
     * @param headers request headers
     * @return page or redirect
     */
    @GET
    @Path("onboarding")
    @Operation(hidden = true)
    public Response onboardingPage(@QueryParam("next") String next, HttpHeaders headers) {
        Profile profile = currentProfile();
        String safeNext = Redirects.safeNext(next);
        if (profile.onboarded()) {
            return Redirects.to(headers, safeNext).build();
        }
        ProfileFormView view = new ProfileFormView(true, safeNext, ProfileForm.from(profile), Map.of(), profile, null);
        return Response.ok(Templates.onboarding(view)).build();
    }

    /**
     * Completes onboarding.
     *
     * @param username    chosen username
     * @param displayName name shown to groups
     * @param birthMonth  optional month
     * @param birthDay    optional day
     * @param avatar      optional photo
     * @param next        where to go afterwards
     * @param headers     request headers
     * @return redirect on success, or the form with errors
     */
    @POST
    @Path("onboarding")
    @Operation(hidden = true)
    public Response completeOnboarding(@RestForm String username, @RestForm String displayName,
                                       @RestForm String birthMonth, @RestForm String birthDay,
                                       @RestForm FileUpload avatar, @RestForm String next, HttpHeaders headers) {
        String safeNext = Redirects.safeNext(next);
        ProfileForm form = ProfileForm.of(username, displayName, birthMonth, birthDay);
        Result result = save(form, avatar, true);
        if (!result.errors().isEmpty()) {
            return Response.ok(Templates.profileForm(
                    new ProfileFormView(true, safeNext, form, result.errors(), result.profile(), null))).build();
        }
        return Redirects.to(headers, safeNext).build();
    }

    /**
     * Live username availability check for the form.
     *
     * @param username candidate username
     * @return status fragment
     */
    @GET
    @Path("onboarding/username-check")
    @Operation(hidden = true)
    public TemplateInstance usernameCheck(@QueryParam("username") String username) {
        String candidate = username == null ? null : username.strip();
        String formatError = ProfileForm.usernameError(candidate == null || candidate.isEmpty() ? null : candidate);
        if (formatError != null) {
            return Templates.usernameStatus(formatError, false);
        }
        return profiles.isUsernameAvailable(jwt, candidate)
                ? Templates.usernameStatus("“" + candidate + "” is available.", true)
                : Templates.usernameStatus(ProfileService.USERNAME_TAKEN, false);
    }

    /**
     * Profile edit page.
     *
     * @return page
     */
    @GET
    @Path("profile")
    @RequiresOnboarding
    @Operation(hidden = true)
    public TemplateInstance profilePage() {
        Profile profile = currentProfile();
        return Templates.profile(new ProfileFormView(false, "/", ProfileForm.from(profile), Map.of(), profile, null));
    }

    /**
     * Saves profile edits.
     *
     * @param username    username
     * @param displayName name shown to groups
     * @param birthMonth  optional month
     * @param birthDay    optional day
     * @param avatar      optional new photo
     * @return the form, with errors or a confirmation
     */
    @POST
    @Path("profile")
    @RequiresOnboarding
    @Operation(hidden = true)
    public TemplateInstance saveProfile(@RestForm String username, @RestForm String displayName,
                                        @RestForm String birthMonth, @RestForm String birthDay,
                                        @RestForm FileUpload avatar) {
        ProfileForm form = ProfileForm.of(username, displayName, birthMonth, birthDay);
        Result result = save(form, avatar, false);
        String saved = result.errors().isEmpty() ? "Your profile is saved." : null;
        ProfileForm shown = result.errors().isEmpty() ? ProfileForm.from(result.profile()) : form;
        return Templates.profileForm(new ProfileFormView(false, "/", shown, result.errors(), result.profile(), saved));
    }

    /**
     * Removes the caller's avatar.
     *
     * @return the form with a confirmation
     */
    @POST
    @Path("profile/avatar/remove")
    @RequiresOnboarding
    @Operation(hidden = true)
    public TemplateInstance removeAvatar() {
        avatars.remove(jwt, currentProfile());
        Profile updated = currentProfile();
        return Templates.profileForm(new ProfileFormView(false, "/", ProfileForm.from(updated), Map.of(), updated,
                "Your photo was removed."));
    }

    /**
     * Outcome of a save: errors (empty on success) and the latest profile.
     *
     * @param errors  field errors
     * @param profile profile after the attempt
     */
    private record Result(Map<String, String> errors, Profile profile) {
    }

    /**
     * Validates everything (including the photo) before writing anything.
     *
     * @param form               submitted fields
     * @param avatar             optional upload
     * @param completeOnboarding whether to mark onboarding complete
     * @return result
     */
    private Result save(ProfileForm form, FileUpload avatar, boolean completeOnboarding) {
        Profile current = currentProfile();
        Map<String, String> errors = new LinkedHashMap<>(form.validate());
        byte[] jpeg = null;
        if (hasFile(avatar)) {
            try {
                jpeg = AvatarImages.normalize(read(avatar));
            } catch (AvatarImages.InvalidAvatarException e) {
                errors.put("avatar", e.getMessage());
            }
        }
        if (errors.isEmpty()) {
            errors.putAll(profiles.save(jwt, form, completeOnboarding));
        }
        if (errors.isEmpty() && jpeg != null) {
            avatars.replace(jwt, current, jpeg);
        }
        if (!errors.isEmpty()) {
            LOG.debugf("Profile form rejected for %s: %s", jwt.getSubject(), errors.keySet());
        }
        return new Result(errors, currentProfile());
    }

    private Profile currentProfile() {
        return profiles.findOwn(jwt).orElseThrow(() -> new IllegalStateException(
                "Signed-in user has no Profile row: " + jwt.getSubject()));
    }

    private static boolean hasFile(FileUpload upload) {
        return upload != null && upload.size() > 0 && upload.fileName() != null && !upload.fileName().isBlank();
    }

    private static byte[] read(FileUpload upload) throws AvatarImages.InvalidAvatarException {
        if (upload.size() > AvatarImages.MAX_BYTES) {
            throw new AvatarImages.InvalidAvatarException("That photo is too large. Please choose one under 2 MB.");
        }
        try {
            return Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
