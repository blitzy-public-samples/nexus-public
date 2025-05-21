/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.internal.security.apikey.store;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.sonatype.nexus.internal.security.apikey.ApiKeyInternal;

import org.apache.shiro.subject.PrincipalCollection;

/**
 * Interface for API key storage operations.
 * <p>
 * This interface is designed to be compatible with Java 21 Virtual Threads. All implementations
 * should ensure thread-safety and avoid operations that could cause thread pinning (such as
 * synchronized blocks/methods or native calls).
 * <p>
 * When using this interface with Virtual Threads:
 * <ul>
 *   <li>Methods may block for I/O operations, as Virtual Threads are optimized for this use case</li>
 *   <li>Implementations should avoid thread-local storage which may not work as expected with Virtual Threads</li>
 *   <li>For high-throughput scenarios, consider using the asynchronous methods that return CompletableFuture</li>
 * </ul>
 */
public interface ApiKeyStore
{
  /**
   * Browse tokens in the domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to browse
   * @return collection of API keys in the domain
   */
  Collection<ApiKeyInternal> browse(String domain);

  /**
   * Asynchronously browse tokens in the domain.
   * <p>
   * Non-blocking alternative to {@link #browse(String)} for use in high-throughput scenarios.
   *
   * @param domain the domain to browse
   * @return future that completes with the collection of API keys
   * @since Java 21
   */
  default CompletableFuture<Collection<ApiKeyInternal>> browseAsync(String domain) {
    return CompletableFuture.supplyAsync(() -> browse(domain));
  }

  /**
   * Browse tokens in the domain created after the provided date.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to browse
   * @param date the date after which tokens were created
   * @return collection of API keys created after the specified date
   */
  Collection<ApiKeyInternal> browseByCreatedDate(String domain, OffsetDateTime date);

  /**
   * Asynchronously browse tokens in the domain created after the provided date.
   * <p>
   * Non-blocking alternative to {@link #browseByCreatedDate(String, OffsetDateTime)} for use in high-throughput scenarios.
   *
   * @param domain the domain to browse
   * @param date the date after which tokens were created
   * @return future that completes with the collection of API keys
   * @since Java 21
   */
  default CompletableFuture<Collection<ApiKeyInternal>> browseByCreatedDateAsync(String domain, OffsetDateTime date) {
    return CompletableFuture.supplyAsync(() -> browseByCreatedDate(domain, date));
  }

  /**
   * Browse tokens in the domain (paginated).
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to browse
   * @param page the page number (zero-based)
   * @param pageSize the size of each page
   * @return collection of API keys for the specified page
   */
  Collection<ApiKeyInternal> browsePaginated(String domain, int page, int pageSize);

  /**
   * Asynchronously browse tokens in the domain (paginated).
   * <p>
   * Non-blocking alternative to {@link #browsePaginated(String, int, int)} for use in high-throughput scenarios.
   *
   * @param domain the domain to browse
   * @param page the page number (zero-based)
   * @param pageSize the size of each page
   * @return future that completes with the collection of API keys
   * @since Java 21
   */
  default CompletableFuture<Collection<ApiKeyInternal>> browsePaginatedAsync(String domain, int page, int pageSize) {
    return CompletableFuture.supplyAsync(() -> browsePaginated(domain, page, pageSize));
  }

  /**
   * Count all the keys for the provided domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to count keys for
   * @return the number of keys in the domain
   */
  int count(String domain);

  /**
   * Asynchronously count all the keys for the provided domain.
   * <p>
   * Non-blocking alternative to {@link #count(String)} for use in high-throughput scenarios.
   *
   * @param domain the domain to count keys for
   * @return future that completes with the count
   * @since Java 21
   */
  default CompletableFuture<Integer> countAsync(String domain) {
    return CompletableFuture.supplyAsync(() -> count(domain));
  }

  /**
   * Deletes the API-Key associated with the given principals in given domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain containing the API key
   * @param principals the principals associated with the API key
   * @return the number of keys deleted
   */
  int deleteApiKey(String domain, PrincipalCollection principals);

  /**
   * Asynchronously delete the API-Key associated with the given principals in given domain.
   * <p>
   * Non-blocking alternative to {@link #deleteApiKey(String, PrincipalCollection)} for use in high-throughput scenarios.
   *
   * @param domain the domain containing the API key
   * @param principals the principals associated with the API key
   * @return future that completes with the number of keys deleted
   * @since Java 21
   */
  default CompletableFuture<Integer> deleteApiKeyAsync(String domain, PrincipalCollection principals) {
    return CompletableFuture.supplyAsync(() -> deleteApiKey(domain, principals));
  }

  /**
   * Remove all expired API-Keys.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param expiration the expiration date to check against
   * @return the number of keys deleted
   */
  int deleteApiKeys(OffsetDateTime expiration);

  /**
   * Asynchronously remove all expired API-Keys.
   * <p>
   * Non-blocking alternative to {@link #deleteApiKeys(OffsetDateTime)} for use in high-throughput scenarios.
   *
   * @param expiration the expiration date to check against
   * @return future that completes with the number of keys deleted
   * @since Java 21
   */
  default CompletableFuture<Integer> deleteApiKeysAsync(OffsetDateTime expiration) {
    return CompletableFuture.supplyAsync(() -> deleteApiKeys(expiration));
  }

  /**
   * Deletes every API-Key associated with the given principals in every domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param principals the principals associated with the API keys
   * @return the number of keys deleted
   */
  int deleteApiKeys(PrincipalCollection principals);

  /**
   * Asynchronously delete every API-Key associated with the given principals in every domain.
   * <p>
   * Non-blocking alternative to {@link #deleteApiKeys(PrincipalCollection)} for use in high-throughput scenarios.
   *
   * @param principals the principals associated with the API keys
   * @return future that completes with the number of keys deleted
   * @since Java 21
   */
  default CompletableFuture<Integer> deleteApiKeysAsync(PrincipalCollection principals) {
    return CompletableFuture.supplyAsync(() -> deleteApiKeys(principals));
  }

  /**
   * Deletes all API-Keys for the specified domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to delete keys from
   * @return the number of keys deleted
   */
  int deleteApiKeys(String domain);

  /**
   * Asynchronously delete all API-Keys for the specified domain.
   * <p>
   * Non-blocking alternative to {@link #deleteApiKeys(String)} for use in high-throughput scenarios.
   *
   * @param domain the domain to delete keys from
   * @return future that completes with the number of keys deleted
   * @since Java 21
   */
  default CompletableFuture<Integer> deleteApiKeysAsync(String domain) {
    return CompletableFuture.supplyAsync(() -> deleteApiKeys(domain));
  }

  /**
   * Gets the current API-Key assigned to the given principals in given domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to search in
   * @param principals the principals to look up
   * @return an Optional containing the API key if found, or empty if not found
   */
  Optional<ApiKeyInternal> getApiKey(String domain, PrincipalCollection principals);

  /**
   * Asynchronously get the current API-Key assigned to the given principals in given domain.
   * <p>
   * Non-blocking alternative to {@link #getApiKey(String, PrincipalCollection)} for use in high-throughput scenarios.
   *
   * @param domain the domain to search in
   * @param principals the principals to look up
   * @return future that completes with an Optional containing the API key if found
   * @since Java 21
   */
  default CompletableFuture<Optional<ApiKeyInternal>> getApiKeyAsync(String domain, PrincipalCollection principals) {
    return CompletableFuture.supplyAsync(() -> getApiKey(domain, principals));
  }

  /**
   * Retrieves the principals associated with the given API-Key in given domain.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to search in
   * @param apiKey the API key to look up
   * @return an Optional containing the API key if found, or empty if the key is invalid or stale
   */
  Optional<ApiKeyInternal> getApiKeyByToken(String domain, char[] apiKey);

  /**
   * Asynchronously retrieve the principals associated with the given API-Key in given domain.
   * <p>
   * Non-blocking alternative to {@link #getApiKeyByToken(String, char[])} for use in high-throughput scenarios.
   *
   * @param domain the domain to search in
   * @param apiKey the API key to look up
   * @return future that completes with an Optional containing the API key if found
   * @since Java 21
   */
  default CompletableFuture<Optional<ApiKeyInternal>> getApiKeyByTokenAsync(String domain, char[] apiKey) {
    return CompletableFuture.supplyAsync(() -> getApiKeyByToken(domain, apiKey));
  }

  /**
   * Persists an API-Key with a predetermined value.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to store the key in
   * @param principals the principals to associate with the key
   * @param apiKey the API key value
   * @since 3.1
   */
  default void persistApiKey(final String domain, final PrincipalCollection principals, final char[] apiKey) {
    persistApiKey(domain, principals, apiKey, null);
  }

  /**
   * Asynchronously persist an API-Key with a predetermined value.
   * <p>
   * Non-blocking alternative to {@link #persistApiKey(String, PrincipalCollection, char[])} for use in high-throughput scenarios.
   *
   * @param domain the domain to store the key in
   * @param principals the principals to associate with the key
   * @param apiKey the API key value
   * @return future that completes when the operation is done
   * @since Java 21
   */
  default CompletableFuture<Void> persistApiKeyAsync(final String domain, final PrincipalCollection principals, final char[] apiKey) {
    return CompletableFuture.runAsync(() -> persistApiKey(domain, principals, apiKey));
  }

  /**
   * Persists an API-Key with a predetermined value.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param domain the domain to store the key in
   * @param principals the principals to associate with the key
   * @param apiKey the API key value
   * @param created the creation timestamp, or null to use current time
   */
  void persistApiKey(String domain, PrincipalCollection principals, char[] apiKey, @Nullable OffsetDateTime created);

  /**
   * Asynchronously persist an API-Key with a predetermined value.
   * <p>
   * Non-blocking alternative to {@link #persistApiKey(String, PrincipalCollection, char[], OffsetDateTime)} for use in high-throughput scenarios.
   *
   * @param domain the domain to store the key in
   * @param principals the principals to associate with the key
   * @param apiKey the API key value
   * @param created the creation timestamp, or null to use current time
   * @return future that completes when the operation is done
   * @since Java 21
   */
  default CompletableFuture<Void> persistApiKeyAsync(String domain, PrincipalCollection principals, char[] apiKey, @Nullable OffsetDateTime created) {
    return CompletableFuture.runAsync(() -> persistApiKey(domain, principals, apiKey, created));
  }

  /**
   * Updates an existing API-key.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @param from the API key to update
   * @param newPrincipal the new principal to associate with the key
   */
  void updateApiKey(ApiKeyInternal from, PrincipalCollection newPrincipal);

  /**
   * Asynchronously update an existing API-key.
   * <p>
   * Non-blocking alternative to {@link #updateApiKey(ApiKeyInternal, PrincipalCollection)} for use in high-throughput scenarios.
   *
   * @param from the API key to update
   * @param newPrincipal the new principal to associate with the key
   * @return future that completes when the operation is done
   * @since Java 21
   */
  default CompletableFuture<Void> updateApiKeyAsync(ApiKeyInternal from, PrincipalCollection newPrincipal) {
    return CompletableFuture.runAsync(() -> updateApiKey(from, newPrincipal));
  }

  /**
   * Browse all principals across all domains.
   * <p>
   * This operation may block for I/O and is suitable for execution in a Virtual Thread.
   *
   * @return iterable of all principals
   */
  Iterable<PrincipalCollection> browsePrincipals();

  /**
   * Asynchronously browse all principals across all domains.
   * <p>
   * Non-blocking alternative to {@link #browsePrincipals()} for use in high-throughput scenarios.
   *
   * @return future that completes with an iterable of all principals
   * @since Java 21
   */
  default CompletableFuture<Iterable<PrincipalCollection>> browsePrincipalsAsync() {
    return CompletableFuture.supplyAsync(this::browsePrincipals);
  }
}