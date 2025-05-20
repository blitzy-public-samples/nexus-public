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
package org.sonatype.nexus.repository.security.internal.secrets.migration;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.thread.VirtualThreadExecutors;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.crypto.secrets.SecretsService;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.security.secrets.SecretMigrationException;
import org.sonatype.nexus.security.secrets.SecretsMigratorSupport;

import com.google.common.annotations.VisibleForTesting;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migrates repository secrets from legacy encryption to the new secrets storage system.
 * 
 * @since 3.60
 */
@Named
public class RepositoriesSecretsMigrator
    extends SecretsMigratorSupport
{
  @VisibleForTesting
  static final String HTTP_CLIENT_KEY = "httpclient";

  @VisibleForTesting
  static final String AUTHENTICATION_KEY = "authentication";

  @VisibleForTesting
  static final String BEARER_TOKEN_KEY = "bearerToken";

  @VisibleForTesting
  static final String PASSWORD_KEY = "password";

  private final RepositoryManager repositoryManager;

  @Inject
  public RepositoriesSecretsMigrator(final SecretsService secretsService, final RepositoryManager repositoryManager)
  {
    super(secretsService);
    this.repositoryManager = checkNotNull(repositoryManager);
  }

  @Override
  public void migrate() {
    // Get all repositories in a single bulk operation
    List<Repository> repositories = repositoryManager.browse();
    
    // Create a virtual thread executor for parallel processing
    try (ExecutorService executor = VirtualThreadExecutors.newVirtualThreadPerTaskExecutor()) {
      // Process proxy repositories in parallel using Virtual Threads
      List<CompletableFuture<Void>> futures = repositories.stream()
          .filter(repository -> repository.getType() instanceof ProxyType)
          .map(repository -> CompletableFuture.runAsync(() -> {
            CancelableHelper.checkCancellation();
            migrateProxy(repository);
          }, executor))
          .collect(Collectors.toList());
      
      // Wait for all migrations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
  }

  private void migrateProxy(final Repository repository) {
    Configuration configuration = repository.getConfiguration().copy();
    boolean needUpdate = false;

    // Use Pattern Matching for switch to handle the Map structure more elegantly
    Map<String, Object> authConfig = switch (configuration.getAttributes()) {
      case null -> Collections.emptyMap();
      case Map<String, Object> globalAttrs -> {
        Object httpClient = globalAttrs.get(HTTP_CLIENT_KEY);
        if (httpClient instanceof Map<?, ?> httpAttrs) {
          Object auth = httpAttrs.get(AUTHENTICATION_KEY);
          if (auth instanceof Map<?, ?> authAttrs) {
            yield (Map<String, Object>) authAttrs;
          }
        }
        yield Collections.emptyMap();
      }
    };

    // Process password if present
    Object passwordObj = authConfig.get(PASSWORD_KEY);
    if (passwordObj instanceof String password) {
      Secret passwordKey = secretsService.from(password);
      if (isLegacyEncryptedString(passwordKey)) {
        try {
          // Decrypt using Java 21 compatible methods
          char[] decryptedChars = passwordKey.decrypt();
          authConfig.put(PASSWORD_KEY, new String(decryptedChars));
          needUpdate = true;
        }
        catch (Exception e) {
          log.error("Failed to decrypt password for repository {}: {}", 
              repository.getName(), e.getMessage(), log.isDebugEnabled() ? e : null);
        }
      }
    }

    if (needUpdate) {
      save(configuration);
    }
  }

  /**
   * Updates a repository configuration, handling exceptions according to Java 21 best practices.
   * If a failure occurs then secrets will be removed.
   *
   * @param configuration the repository configuration to update
   * @throws SecretMigrationException if the migration fails
   */
  private void save(final Configuration configuration) {
    try {
      // repository manager encrypts and handles removal in case of failure
      repositoryManager.update(configuration);
    }
    catch (Exception e) {
      String repoName = configuration.getRepositoryName();
      log.error("Failed to migrate repository {}: {}", repoName, e.getMessage());
      throw new SecretMigrationException("Failed to migrate repository: " + repoName, e);
    }
  }
}
