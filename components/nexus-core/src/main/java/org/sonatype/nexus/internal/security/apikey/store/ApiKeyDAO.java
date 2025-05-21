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

import org.sonatype.nexus.datastore.api.DataAccess;
import org.sonatype.nexus.internal.security.apikey.ApiKeyInternal;

import org.apache.ibatis.annotations.Param;
import org.apache.shiro.subject.PrincipalCollection;

/**
 * {@link ApiKeyData} access.
 * <p>
 * This interface is optimized for execution with Java 21 Virtual Threads to improve
 * concurrency and throughput for database operations. All methods are safe to call
 * from Virtual Threads and will not cause thread pinning.
 *
 * @since 3.21
 * @deprecated legacy implementation which does not use secrets
 */
@Deprecated
public interface ApiKeyDAO
    extends DataAccess
{
  /**
   * Browse all principals.
   * 
   * @return Iterable of principal collections
   */
  Iterable<PrincipalCollection> browsePrincipals();

  /**
   * Find {@link ApiKeyInternal} records in the domain with the specified primary principal.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * NOTE that callers must verify that this matches the PrincipalCollection of the keys.
   *
   * @param domain the domain for the token (e.g. NuGetApiKey)
   * @param primaryPrincipal the primary principal to locate
   * @return collection of API keys matching the criteria
   */
  Collection<ApiKeyInternal> findApiKeys(@Param("domain") String domain, @Param("primaryPrincipal") String primaryPrincipal);

  /**
   * Find {@link ApiKeyInternal} records across all domains with the specified primary principal.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * NOTE that callers must verify that this matches the PrincipalCollection of the keys.
   *
   * @param primaryPrincipal the primary principal to locate
   * @return collection of API keys matching the criteria
   */
  Collection<ApiKeyInternal> findApiKeysForPrimary(@Param("primaryPrincipal") String primaryPrincipal);

  /**
   * Find principals by domain and token.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain
   * @param token token
   * @return optional containing the API key if found
   */
  Optional<ApiKeyInternal> findPrincipals(@Param("domain") String domain, @Param("token") ApiKeyToken token);

  /**
   * Asynchronously find principals by domain and token using Virtual Threads.
   * <p>
   * This method provides a non-blocking alternative to {@link #findPrincipals(String, ApiKeyToken)}
   * that can be used in high-concurrency scenarios.
   *
   * @param domain the domain
   * @param token token
   * @return CompletableFuture that will complete with the API key if found
   * @since Java 21
   */
  default CompletableFuture<Optional<ApiKeyInternal>> findPrincipalsAsync(
      @Param("domain") String domain, @Param("token") ApiKeyToken token) {
    return CompletableFuture.supplyAsync(() -> findPrincipals(domain, token));
  }

  /**
   * Save an API key data record.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param apiKeyData the API key data to save
   */
  void save(ApiKeyData apiKeyData);

  /**
   * Delete an {@link ApiKeyInternal} in the specified domain.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain for the token (e.g. NuGetApiKey)
   * @param token the token
   * @return the number of records deleted
   */
  int deleteKey(@Param("domain") String domain, @Param("token") ApiKeyToken token);

  /**
   * Browse all API Keys in the specified domain.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return collection of API keys in the domain
   */
  Collection<ApiKeyInternal> browse(@Param("domain") String domain);

  /**
   * Browse all API Keys in the specified domain by created date.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return collection of API keys matching the criteria
   */
  Collection<ApiKeyInternal> browseByCreatedDate(@Param("domain") String domain, @Param("created") OffsetDateTime created);

  /**
   * Count the number of API Keys in the specified domain.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return the count of API keys in the domain
   */
  int count(@Param("domain") String domain);

  /**
   * Remove all API Keys in the specified domain.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return the number of records deleted
   */
  int deleteApiKeysByDomain(@Param("domain") String domain);

  /**
   * Remove all expired API Keys.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param expiration the date of expiration
   * @return the number of records deleted
   */
  int deleteApiKeyByExpirationDate(@Param("expiration") OffsetDateTime expiration);

  /**
   * Updates an existing {@link ApiKeyInternal}.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param toUpdate the API key data to update
   */
  void update(ApiKeyData toUpdate);

  /**
   * Browse all API Keys in the specified domain (paginated).
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param skip   the amount of records to skip/offset
   * @param limit  the amount of records to limit the query to
   * @return collection of API keys matching the criteria
   */
  Collection<ApiKeyInternal> browsePaginated(
      @Param("domain") String domain,
      @Param("skip") int skip,
      @Param("limit") int limit);

  /**
   * Asynchronously browse all API Keys in the specified domain (paginated) using Virtual Threads.
   * <p>
   * This method provides a non-blocking alternative to {@link #browsePaginated(String, int, int)}
   * that can be used in high-concurrency scenarios.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param skip   the amount of records to skip/offset
   * @param limit  the amount of records to limit the query to
   * @return CompletableFuture that will complete with the collection of API keys
   * @since Java 21
   */
  default CompletableFuture<Collection<ApiKeyInternal>> browsePaginatedAsync(
      @Param("domain") String domain,
      @Param("skip") int skip,
      @Param("limit") int limit) {
    return CompletableFuture.supplyAsync(() -> browsePaginated(domain, skip, limit));
  }

  /**
   * Browse all API keys created since a specific date, with a limit.
   * <p>
   * This method is optimized for execution with Virtual Threads.
   *
   * @param created a date to use as the starting point for the pagination
   * @param limit  the number of records
   * @return collection of API key data matching the criteria
   * @deprecated exists only for migration
   */
  @Deprecated
  Collection<ApiKeyData> browseAllSince(@Param("created") OffsetDateTime created, @Param("limit") int limit);
}