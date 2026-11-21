package com.giftloop.observability;

import com.giftloop.supabase.SupabaseAuthApi;
import io.smallrye.health.api.AsyncHealthCheck;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.time.Duration;

/**
 * Readiness: sign-in and session refresh need Supabase Auth, so the app is not ready
 * to take traffic if Auth is unreachable. (The database is covered by Agroal's check.)
 */
@Readiness
@ApplicationScoped
public class SupabaseAuthReadiness implements AsyncHealthCheck {

    static final String NAME = "Supabase Auth";
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final SupabaseAuthApi auth;

    /**
     * Creates the check.
     *
     * @param auth Supabase Auth REST client
     */
    public SupabaseAuthReadiness(@RestClient SupabaseAuthApi auth) {
        this.auth = auth;
    }

    /**
     * Calls {@code /auth/v1/health} without blocking.
     *
     * @return UP if Auth answers within {@link #TIMEOUT}, else DOWN with the reason
     */
    @Override
    public Uni<HealthCheckResponse> call() {
        return auth.healthAsync()
                .ifNoItem().after(TIMEOUT).fail()
                .map(ignored -> HealthCheckResponse.up(NAME))
                .onFailure().recoverWithItem(e -> HealthCheckResponse.named(NAME).down()
                        .withData("error", e.getClass().getSimpleName()).build());
    }
}
