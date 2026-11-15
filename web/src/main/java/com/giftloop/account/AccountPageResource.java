package com.giftloop.account;

import com.giftloop.profile.AvatarService;
import com.giftloop.profile.Profile;
import com.giftloop.profile.ProfileService;
import com.giftloop.security.RequiresOnboarding;
import com.giftloop.security.SessionCookies;
import com.giftloop.wishlist.GiftImageService;
import com.giftloop.web.Redirects;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.Authenticated;
import io.quarkus.security.UnauthorizedException;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestForm;

import java.util.List;

/**
 * Account deletion: owners pick who takes over each group (or leave it automatic),
 * then confirm by typing their username.
 */
@Path("/account")
@Produces(MediaType.TEXT_HTML)
@Authenticated
public class AccountPageResource {

    private static final Logger LOG = Logger.getLogger(AccountPageResource.class);

    private final JsonWebToken jwt;
    private final AccountService accounts;
    private final ProfileService profiles;
    private final AvatarService avatars;
    private final GiftImageService giftImages;
    private final SessionCookies cookies;

    /**
     * Creates the resource.
     *
     * @param jwt      caller's verified token
     * @param accounts account deletion
     * @param profiles profile access
     * @param avatars  avatar storage
     * @param giftImages gift photo storage (deletes photos of gifts removed with the account)
     * @param cookies  session cookies
     */
    public AccountPageResource(JsonWebToken jwt, AccountService accounts, ProfileService profiles,
                               AvatarService avatars, GiftImageService giftImages, SessionCookies cookies) {
        this.jwt = jwt;
        this.accounts = accounts;
        this.profiles = profiles;
        this.avatars = avatars;
        this.giftImages = giftImages;
        this.cookies = cookies;
    }

    /**
     * Templates under {@code templates/AccountPageResource/}.
     */
    @CheckedTemplate
    static class Templates {
        /**
         * Deletion page.
         *
         * @param view page data
         * @return page
         */
        static native TemplateInstance delete(AccountDeletionView view);

        /**
         * Deletion form fragment.
         *
         * @param view page data
         * @return fragment
         */
        static native TemplateInstance deleteForm(AccountDeletionView view);

        /**
         * Shown after the account is deleted.
         *
         * @return page
         */
        static native TemplateInstance deleted();
    }

    /**
     * Account deletion page.
     *
     * @return page
     */
    @GET
    @Path("delete")
    @RequiresOnboarding
    @Operation(hidden = true)
    public TemplateInstance deletePage() {
        return Templates.delete(AccountDeletionView.initial(currentProfile().username(), accounts.ownedGroups(jwt)));
    }

    /**
     * Deletes the caller's account after handing off chosen groups.
     *
     * @param username   username typed to confirm
     * @param successors {@code <groupId>:<userId|auto>} per owned group with other members
     * @param headers    request headers
     * @return redirect to the goodbye page with cleared cookies, or the form with an error
     */
    @POST
    @Path("delete")
    @RequiresOnboarding
    @Operation(hidden = true)
    public Response delete(@RestForm String username, @RestForm("successor") List<String> successors,
                           HttpHeaders headers) {
        Profile profile = currentProfile();
        AccountDeletionForm form = AccountDeletionForm.parse(username, successors);
        String error = form.usernameError(profile.username());
        if (error == null) {
            try {
                accounts.delete(jwt, form);
            } catch (AccountDeletionRefusedException e) {
                error = e.getMessage();
            }
        }
        if (error != null) {
            LOG.debugf("Account deletion form rejected for %s", jwt.getSubject());
            List<OwnedGroup> groups = accounts.ownedGroups(jwt);
            return Response.ok(Templates.deleteForm(
                    new AccountDeletionView(profile.username(), groups, form, error))).build();
        }
        // The access token still verifies until it expires, so Storage RLS lets us remove the file.
        avatars.deleteFile(jwt, profile.avatarPath());
        giftImages.sweep(jwt);
        return Redirects.to(headers, "/account/deleted").cookie(cookies.clear()).build();
    }

    /**
     * Confirmation after deletion. Public: the session is already gone.
     *
     * @return page
     */
    @GET
    @Path("deleted")
    @PermitAll
    @Operation(hidden = true)
    public TemplateInstance deletedPage() {
        return Templates.deleted();
    }

    private Profile currentProfile() {
        // No row: the account was deleted while this token is still valid; treat as signed out.
        return profiles.findOwn(jwt).orElseThrow(UnauthorizedException::new);
    }
}
