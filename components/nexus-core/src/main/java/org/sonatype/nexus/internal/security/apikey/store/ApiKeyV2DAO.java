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
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;
import org.apache.shiro.subject.PrincipalCollection;

/**
 * {@link ApiKeyV2Data} access.
 *
 * @since 3.21
 */
public interface ApiKeyV2DAO
    extends DataAccess
{
  /**
   * Marker annotation to indicate methods that are optimized for Virtual Thread execution.
   * Methods with this annotation are designed to be non-blocking and can be efficiently
   * executed on Java 21 Virtual Threads.
   *
   * @since 3.60
   */
  @interface VirtualThreadOptimized {}

  /**
   * Browse all API Keys in the specified domain
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return collection of API keys in the domain
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyInternal> browse(@Param("domain") String domain);

  /**
   * Asynchronously browse all API Keys in the specified domain.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return future that completes with a collection of API keys in the domain
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyInternal>> browseAsync(@Param("domain") String domain) {
    return CompletableFuture.supplyAsync(() -> browse(domain));
  }

  /**
   * Browse all API Keys across all domains
   *
   * @param created the date created
   * @return collection of API key data created before the specified date
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyV2Data> browseCreatedBefore(@Param("created") OffsetDateTime created);

  /**
   * Asynchronously browse all API Keys across all domains created before the specified date.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param created the date created
   * @return future that completes with a collection of API key data
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyV2Data>> browseCreatedBeforeAsync(@Param("created") OffsetDateTime created) {
    return CompletableFuture.supplyAsync(() -> browseCreatedBefore(created));
  }

  /**
   * Browse all API Keys in the specified domain after the specified date.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return collection of API keys created after the specified date
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyInternal> browseCreatedAfter(
      @Param("domain") String domain,
      @Param("created") OffsetDateTime created);

  /**
   * Asynchronously browse all API Keys in the specified domain after the specified date.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return future that completes with a collection of API keys
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyInternal>> browseCreatedAfterAsync(
      @Param("domain") String domain,
      @Param("created") OffsetDateTime created) {
    return CompletableFuture.supplyAsync(() -> browseCreatedAfter(domain, created));
  }

  /**
   * Browse all API Keys in the specified domain before the specified date.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return collection of API keys created before the specified date
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyInternal> browseCreatedBefore(
      @Param("domain") String domain,
      @Param("created") OffsetDateTime created);

  /**
   * Asynchronously browse all API Keys in the specified domain before the specified date.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param created the date created
   * @return future that completes with a collection of API keys
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyInternal>> browseCreatedBeforeAsync(
      @Param("domain") String domain,
      @Param("created") OffsetDateTime created) {
    return CompletableFuture.supplyAsync(() -> browseCreatedBefore(domain, created));
  }

  /**
   * Browse all API Keys in the specified domain (paginated)
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param skip   the amount of records to skip/offset
   * @param limit  the amount of records to limit the query to
   * @return paginated collection of API keys
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyInternal> browsePaginated(
      @Param("domain") String domain,
      @Param("skip") int skip,
      @Param("limit") int limit);

  /**
   * Asynchronously browse all API Keys in the specified domain (paginated).
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @param skip   the amount of records to skip/offset
   * @param limit  the amount of records to limit the query to
   * @return future that completes with a paginated collection of API keys
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyInternal>> browsePaginatedAsync(
      @Param("domain") String domain,
      @Param("skip") int skip,
      @Param("limit") int limit) {
    return CompletableFuture.supplyAsync(() -> browsePaginated(domain, skip, limit));
  }

  /**
   * Browse all principals associated with API keys.
   *
   * @return iterable of principal collections
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Iterable<PrincipalCollection> browsePrincipals();

  /**
   * Asynchronously browse all principals associated with API keys.
   * This method is optimized for execution on Virtual Threads.
   *
   * @return future that completes with an iterable of principal collections
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Iterable<PrincipalCollection>> browsePrincipalsAsync() {
    return CompletableFuture.supplyAsync(this::browsePrincipals);
  }

  /**
   * Count the number of API Keys in the specified domain
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return count of API keys in the domain
   */
  @VirtualThreadOptimized
  @Select("SELECT COUNT(*) FROM ${schema}.api_key WHERE domain = #{domain}")
  int count(@Param("domain") String domain);

  /**
   * Asynchronously count the number of API Keys in the specified domain.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain, e.g. npm keys, nuget keys
   * @return future that completes with the count of API keys
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Integer> countAsync(@Param("domain") String domain) {
    return CompletableFuture.supplyAsync(() -> count(domain));
  }

  /**
   * Remove the api key for the specified user in the domain. The associated secret should also be removed.
   *
   * @param apiTokenData the token to remove
   * @return number of records deleted
   */
  @VirtualThreadOptimized
  int deleteApiKey(ApiKeyV2Data apiTokenData);

  /**
   * Asynchronously remove the api key for the specified user in the domain.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param apiTokenData the token to remove
   * @return future that completes with the number of records deleted
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Integer> deleteApiKeyAsync(ApiKeyV2Data apiTokenData) {
    return CompletableFuture.supplyAsync(() -> deleteApiKey(apiTokenData));
  }

  /**
   * Find {@link ApiKeyInternal} record in the domain for the specified user name.
   *
   * @param domain the domain for the token (e.g. NuGetApiKey)
   * @param user  the user name to locate
   * @return collection of API key data for the user in the domain
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyV2Data> findApiKey(
      @Param("domain") String domain,
      @Param("username") String user);

  /**
   * Asynchronously find {@link ApiKeyInternal} record in the domain for the specified user name.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain for the token (e.g. NuGetApiKey)
   * @param user  the user name to locate
   * @return future that completes with a collection of API key data
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyV2Data>> findApiKeyAsync(
      @Param("domain") String domain,
      @Param("username") String user) {
    return CompletableFuture.supplyAsync(() -> findApiKey(domain, user));
  }

  /**
   * Find {@link ApiKeyInternal} records across all domains with the specified user.
   *
   * @param user  the user name to locate
   * @return collection of API key data for the user across all domains
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Collection<ApiKeyV2Data> findApiKeysForUser(@Param("username") String user);

  /**
   * Asynchronously find {@link ApiKeyInternal} records across all domains with the specified user.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param user  the user name to locate
   * @return future that completes with a collection of API key data
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Collection<ApiKeyV2Data>> findApiKeysForUserAsync(@Param("username") String user) {
    return CompletableFuture.supplyAsync(() -> findApiKeysForUser(user));
  }

  /**
   * Find an api key with the matching access key, callers will need to validate the secret matches the expected
   *
   * @param domain the domain
   * @param accessKey access key
   * @return optional containing the API key internal if found
   */
  @VirtualThreadOptimized
  @Options(useCache = false)
  Optional<ApiKeyInternal> findPrincipals(@Param("domain") String domain, @Param("accessKey") String accessKey);

  /**
   * Asynchronously find an api key with the matching access key.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param domain the domain
   * @param accessKey access key
   * @return future that completes with an optional containing the API key internal if found
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Optional<ApiKeyInternal>> findPrincipalsAsync(
      @Param("domain") String domain, 
      @Param("accessKey") String accessKey) {
    return CompletableFuture.supplyAsync(() -> findPrincipals(domain, accessKey));
  }

  /**
   * Save an API token
   *
   * @param token the token
   * @throws DuplicateKeyException if a token with the same key already exists
   */
  @VirtualThreadOptimized
  void save(ApiKeyV2Data token);

  /**
   * Asynchronously save an API token.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param token the token
   * @return future that completes when the save operation is done
   * @throws DuplicateKeyException if a token with the same key already exists
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Void> saveAsync(ApiKeyV2Data token) {
    return CompletableFuture.runAsync(() -> save(token));
  }

  /**
   * Changes the principal associated with a specific token
   *
   * @param token the token
   */
  @VirtualThreadOptimized
  void updatePrincipal(ApiKeyV2Data token);

  /**
   * Asynchronously change the principal associated with a specific token.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param token the token
   * @return future that completes when the update operation is done
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Void> updatePrincipalAsync(ApiKeyV2Data token) {
    return CompletableFuture.runAsync(() -> updatePrincipal(token));
  }

  /**
   * Batch save multiple API tokens in a single transaction.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param tokens the collection of tokens to save
   * @throws DuplicateKeyException if any token with the same key already exists
   * @since 3.60
   */
  @VirtualThreadOptimized
  default void batchSave(Collection<ApiKeyV2Data> tokens) {
    tokens.forEach(this::save);
  }

  /**
   * Asynchronously batch save multiple API tokens in a single transaction.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param tokens the collection of tokens to save
   * @return future that completes when the batch save operation is done
   * @throws DuplicateKeyException if any token with the same key already exists
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Void> batchSaveAsync(Collection<ApiKeyV2Data> tokens) {
    return CompletableFuture.runAsync(() -> batchSave(tokens));
  }

  /**
   * Batch delete multiple API tokens in a single transaction.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param tokens the collection of tokens to delete
   * @return the total number of records deleted
   * @since 3.60
   */
  @VirtualThreadOptimized
  default int batchDelete(Collection<ApiKeyV2Data> tokens) {
    return tokens.stream().mapToInt(this::deleteApiKey).sum();
  }

  /**
   * Asynchronously batch delete multiple API tokens in a single transaction.
   * This method is optimized for execution on Virtual Threads.
   *
   * @param tokens the collection of tokens to delete
   * @return future that completes with the total number of records deleted
   * @since 3.60
   */
  @VirtualThreadOptimized
  default CompletableFuture<Integer> batchDeleteAsync(Collection<ApiKeyV2Data> tokens) {
    return CompletableFuture.supplyAsync(() -> batchDelete(tokens));
  }
}