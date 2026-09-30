/**
 * Google and Apple sign-in (S-18): client registrations from {@code northline.auth.federation.*}, the Apple client
 * secret JWT (generated from the .p8 key and renewed before it expires), authorization requests kept by {@code state}
 * (Apple answers with a cross-site form POST), and the success / failure handlers that hand the person to the Studio.
 */
@NullMarked
package ca.northline.auth.federation;

import org.jspecify.annotations.NullMarked;
