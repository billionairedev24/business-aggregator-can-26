/**
 * OAuth client registration (S-122): the clients declared under {@code northline.oauth.clients.<client-id>} are
 * reconciled into Spring Authorization Server's {@code RegisteredClientRepository} at start-up and by the
 * {@link ca.northline.auth.clients.OAuthClientsCommand} admin command (a Kubernetes Job). Created or updated, never
 * deleted: a client found in the database but not in configuration is only reported.
 */
@NullMarked
package ca.northline.auth.clients;

import org.jspecify.annotations.NullMarked;
