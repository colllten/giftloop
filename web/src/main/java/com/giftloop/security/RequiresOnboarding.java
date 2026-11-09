package com.giftloop.security;

import jakarta.ws.rs.NameBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks pages that signed-in users may only see after finishing onboarding.
 * Anonymous requests pass through (authentication is enforced separately).
 * Enforced by {@link OnboardingGate}.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresOnboarding {
}
