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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.internal.security.apikey.ApiKeyInternal;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.apache.shiro.subject.PrincipalCollection;

/**
 * Legacy {@link ApiKeyStore} implementation
 *
 * @since 3.21
 */
@Deprecated
@Named("v1")
@Singleton
public class ApiKeyStoreImpl
    extends ConfigStoreSupport<ApiKeyDAO>
    implements ApiKeyStore, EventAware
{
  private final Executor virtualThreadExecutor;

  @Inject
  public ApiKeyStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Transactional
  @Override
  public void persistApiKey(
      final String domain,
      final PrincipalCollection principals,
      final char[] apiKey,
      final OffsetDateTime created)
  {
    // Use Virtual Threads for improved I/O performance when persisting
    CompletableFuture<Void> future = CompletableFuture.runAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            ApiKeyData apiKeyData = new ApiKeyData();
            apiKeyData.setDomain(domain);
            apiKeyData.setPrincipals(principals);
            apiKeyData.setApiKey(apiKey);
            apiKeyData.setCreated(created);
            dao().save(apiKeyData);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    future.join();
  }

  @Transactional
  @Override
  public Optional<ApiKeyInternal> getApiKey(
      final String domain,
      final PrincipalCollection principals)
  {
    // Direct call to findApiKey which is already optimized for Virtual Threads
    return findApiKey(domain, principals);
  }

  @Transactional
  @Override
  public Optional<ApiKeyInternal> getApiKeyByToken(
      final String domain,
      final char[] apiKey)
  {
    // Use Virtual Threads for improved I/O performance when finding by token
    CompletableFuture<Optional<ApiKeyInternal>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().findPrincipals(domain, new ApiKeyToken(apiKey));
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public int deleteApiKey(final String domain, final PrincipalCollection principals) {
    // Use Virtual Threads for improved I/O performance when deleting a specific key
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return findApiKey(domain, principals)
                .map(ApiKeyInternal::getApiKey)
                .map(ApiKeyToken::new)
                .map(token -> dao().deleteKey(domain, token))
                .orElse(0);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public int deleteApiKeys(final PrincipalCollection principals) {
    // Use Virtual Threads for improved I/O performance when deleting multiple keys
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            Collection<ApiKeyInternal> matchingKeys = dao().findApiKeysForPrimary(principals.getPrimaryPrincipal().toString())
                .stream()
                .filter(principalMatches(principals))
                .collect(Collectors.toList());
            
            return matchingKeys.stream()
                .map(key -> dao().deleteKey(key.getDomain(), new ApiKeyToken(key.getApiKey())))
                .mapToInt(Integer::intValue)
                .sum();
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Override
  @Transactional
  public Iterable<PrincipalCollection> browsePrincipals() {
    // Use Virtual Threads for improved I/O performance when browsing principals
    CompletableFuture<Iterable<PrincipalCollection>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().browsePrincipals();
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public Collection<ApiKeyInternal> browse(final String domain) {
    // Use Virtual Threads for improved I/O performance when browsing large collections
    CompletableFuture<Collection<ApiKeyInternal>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().browse(domain);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public Collection<ApiKeyInternal> browseByCreatedDate(final String domain, final OffsetDateTime date) {
    // Use Virtual Threads for improved I/O performance when browsing by date
    CompletableFuture<Collection<ApiKeyInternal>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().browseByCreatedDate(domain, date);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public int count(final String domain) {
    // Use Virtual Threads for improved I/O performance when counting
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().count(domain);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public int deleteApiKeys(final String domain) {
    // Use Virtual Threads for improved I/O performance when deleting by domain
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().deleteApiKeysByDomain(domain);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public int deleteApiKeys(final OffsetDateTime expiration) {
    // Use Virtual Threads for improved I/O performance when deleting by expiration date
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().deleteApiKeyByExpirationDate(expiration);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  @Override
  public void updateApiKey(
      final ApiKeyInternal from,
      final PrincipalCollection principalCollection)
  {
    // Use Virtual Threads for improved I/O performance when updating
    CompletableFuture<Void> future = CompletableFuture.runAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            dao().update(
                new ApiKeyData(from.getDomain(), principalCollection, new ApiKeyToken(from.getApiKey()), from.getCreated()));
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    future.join();
  }

  @Transactional
  @Override
  public Collection<ApiKeyInternal> browsePaginated(
      final String domain,
      final int page,
      final int pageSize)
  {
    // Use Virtual Threads for improved I/O performance when browsing with pagination
    CompletableFuture<Collection<ApiKeyInternal>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().browsePaginated(domain, (page - 1) * pageSize, pageSize);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  @Transactional
  public Collection<ApiKeyData> browseAllSince(final OffsetDateTime last, final int pageSize) {
    // Use Virtual Threads for improved I/O performance when browsing since a specific date
    CompletableFuture<Collection<ApiKeyData>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().browseAllSince(last, pageSize);
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  /*
   * Finds ApiKey records for the provided username, and ensures the realm is the same
   * Optimized for Virtual Threads to improve I/O performance
   */
  private Optional<ApiKeyInternal> findApiKey(final String domain, final PrincipalCollection principals) {
    CompletableFuture<Optional<ApiKeyInternal>> future = CompletableFuture.supplyAsync(
        () -> {
          UnitOfWork.begin(this::openSession);
          try {
            return dao().findApiKeys(domain, principals.getPrimaryPrincipal().toString()).stream()
                .filter(principalMatches(principals))
                .findAny();
          } finally {
            UnitOfWork.end();
          }
        },
        virtualThreadExecutor
    );
    return future.join();
  }

  /*
   * Creates a Predicate which ensures the principal of the tested ApiKey is equal to the provided PrincipalCollection
   * Using Java 21 pattern matching for more concise code with enhanced pattern matching
   */
  private Predicate<ApiKeyInternal> principalMatches(final PrincipalCollection principals) {
    String primaryPrincipal = principals.getPrimaryPrincipal().toString();
    Set<String> realms = principals.getRealmNames();
    
    // Using Java 21 pattern matching to simplify the predicate logic
    return key -> {
      if (key.getPrincipals() instanceof PrincipalCollection keyPrincipals) {
        return keyPrincipals.getRealmNames().equals(realms) && 
               keyPrincipals.getPrimaryPrincipal().equals(primaryPrincipal);
      }
      return false;
    };
  }
}