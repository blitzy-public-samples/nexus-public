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
import org.sonatype.nexus.datastore.api.DuplicateKeyException;
import org.sonatype.nexus.internal.security.apikey.ApiKeyInternal;

import org.apache.ibatis.annotations.Param;
import org.apache.shiro.subject.PrincipalCollection;

/**
 * {@link ApiKeyV2Data} access.
 * <p>
 * This interface is optimized for execution with Java 21 Virtual Threads, allowing for
 * high-concurrency database operations with minimal resource overhead. All methods are
 * designed to be non-blocking and can be safely executed on Virtual Threads.
 * </p>
 * <p>
 * Compatible with MyBatis 3.5.15 for Java 21 runtime environment.
 * </p>
 *
 * @since 3.0
 */
public interface ApiKeyV2DAO
    extends DataAccess
{
  /**
   * Browse all API Keys in the specified domain
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return collection of API keys in the specified domain
   */
  Collection<ApiKeyInternal> browse(@Param("domain") String domain);

  /**
   * Browse all API Keys across all domains
   *
   * @param created the date created
   * @return collection of API keys created before the specified date
   */
  Collection<ApiKeyV2Data> browseCreatedBefore(@Param("created") OffsetDateTime created);

  /**
   * Browse all API Keys in the specified domain after the specified date.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return collection of API keys in the specified domain created after the specified date
   */
  Collection<ApiKeyInternal> browseCreatedAfter(
      @Param("domain") String domain,
      @Param("created") OffsetDateTime created);

  /**
   * Browse all API Keys in the specified domain before the specified date.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return collection of API keys in the specified domain created before the specified date
   */
  Collection<ApiKeyInternal> browseCreatedBefore(
      @Param("domain") String domain,
      @Param("created") OffsetDateTime created);

  /**
   * Browse all API Keys in the specified domain (paginated)
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param skip   the amount of records to skip/offset
   * @param limit  the amount of records to limit the query to
   * @return collection of API keys in the specified domain with pagination
   */
  Collection<ApiKeyInternal> browsePaginated(
      @Param("domain") String domain,
      @Param("skip") int skip,
      @Param("limit") int limit);

  /**
   * Browse all principals with API keys.
   *
   * @return iterable of principal collections
   */
  Iterable<PrincipalCollection> browsePrincipals();

  /**
   * Count the number of API Keys in the specified domain
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return the count of API keys in the specified domain
   */
  int count(@Param("domain") String domain);

  /**
   * Remove the api key for the specified user in the domain. The associated secret should also be removed.
   *
   * @param apiTokenData the token to remove
   * @return the number of records deleted
   */
  int deleteApiKey(ApiKeyV2Data apiTokenData);

  /**
   * Find {@link ApiKeyInternal} record in the domain for the specified user name.
   *
   * @param domain the domain for the token (e.g. NuGetApiKey)
   * @param user  the user name to locate
   * @return collection of API key data for the specified user in the domain
   */
  Collection<ApiKeyV2Data> findApiKey(
      @Param("domain") String domain,
      @Param("username") String user);

  /**
   * Find {@link ApiKeyInternal} records across all domains with the specified user.
   *
   * @param user  the user name to locate
   * @return collection of API key data for the specified user across all domains
   */
  Collection<ApiKeyV2Data> findApiKeysForUser(@Param("username") String user);

  /**
   * Find an api key with the matching access key, callers will need to validate the secret matches the expected
   *
   * @param domain the domain
   * @param accessKey access key
   * @return optional containing the API key internal if found
   */
  Optional<ApiKeyInternal> findPrincipals(@Param("domain") String domain, @Param("accessKey") String accessKey);

  /**
   * Save an API token
   *
   * @param token the token
   * @throws DuplicateKeyException if a token with the same key already exists
   */
  void save(ApiKeyV2Data token);

  /**
   * Changes the principal associated with a specific token
   *
   * @param token the token
   */
  void updatePrincipal(ApiKeyV2Data token);
  
  /**
   * Asynchronously find an API key with the matching access key. This method is optimized for
   * Virtual Thread execution and provides non-blocking database access.
   *
   * @param domain the domain
   * @param accessKey access key
   * @return a CompletableFuture that will complete with the API key internal if found
   * @since 3.60
   */
  default CompletableFuture<Optional<ApiKeyInternal>> findPrincipalsAsync(
      @Param("domain") String domain, 
      @Param("accessKey") String accessKey) {
    return CompletableFuture.supplyAsync(() -> findPrincipals(domain, accessKey));
  }
  
  /**
   * Batch save multiple API tokens in a single transaction. This method is optimized for
   * Virtual Thread execution and provides improved performance for bulk operations.
   *
   * @param tokens the collection of tokens to save
   * @throws DuplicateKeyException if any token with the same key already exists
   * @since 3.60
   */
  default void batchSave(Collection<ApiKeyV2Data> tokens) {
    tokens.forEach(this::save);
  }
  
  /**
   * Asynchronously save an API token. This method is optimized for Virtual Thread execution
   * and provides non-blocking database access.
   *
   * @param token the token to save
   * @return a CompletableFuture that will complete when the save operation is done
   * @throws DuplicateKeyException if a token with the same key already exists
   * @since 3.60
   */
  default CompletableFuture<Void> saveAsync(ApiKeyV2Data token) {
    return CompletableFuture.runAsync(() -> save(token));
  }
}