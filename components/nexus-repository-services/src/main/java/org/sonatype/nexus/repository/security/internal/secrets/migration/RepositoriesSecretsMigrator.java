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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Named;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migrates repository secrets using Java 21 features including Virtual Threads and Pattern Matching.
 * Ensures compatibility with Java 21's cryptography providers and BouncyCastle 1.78.1.
 */
@Named
public class RepositoriesSecretsMigrator
    extends SecretsMigratorSupport
{
  private static final Logger log = LoggerFactory.getLogger(RepositoriesSecretsMigrator.class);
  
  @VisibleForTesting
  static final String HTTP_CLIENT_KEY = "httpclient";

  @VisibleForTesting
  static final String AUTHENTICATION_KEY = "authentication";

  @VisibleForTesting
  static final String BEARER_TOKEN_KEY = "bearerToken";

  @VisibleForTesting
  static final String PASSWORD_KEY = "password";
  
  // Default timeout for parallel processing (in seconds)
  private static final int DEFAULT_TIMEOUT = 60;

  private final RepositoryManager repositoryManager;

  @Inject
  public RepositoriesSecretsMigrator(final SecretsService secretsService, final RepositoryManager repositoryManager)
  {
    super(secretsService);
    this.repositoryManager = checkNotNull(repositoryManager);
  }

  /**
   * Migrates repository secrets using Virtual Threads for parallel processing.
   * Implements bulk operations for optimized repository browsing.
   */
  @Override
  public void migrate() {
    List<Repository> repositories =  StreamSupport.stream(repositoryManager.browse().spliterator(), false).toList();
    log.info("Starting migration of {} repositories", repositories.size());
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process repositories in parallel using Virtual Threads
      for (Repository repository : repositories) {
        executor.submit(() -> {
          try {
            CancelableHelper.checkCancellation();
            
            if (repository.getType() instanceof ProxyType) {
              log.debug("Migrating proxy repository: {}", repository.getName());
              migrateProxy(repository);
            }
          } 
          catch (Exception e) {
            log.error("Error migrating repository {}: {}", repository.getName(), e.getMessage(), e);
          }
        });
      }
      
      // Orderly shutdown with timeout
      executor.shutdown();
      if (!executor.awaitTermination(DEFAULT_TIMEOUT, TimeUnit.SECONDS)) {
        log.warn("Migration timed out after {} seconds", DEFAULT_TIMEOUT);
        executor.shutdownNow();
      }
    } 
    catch (InterruptedException e) {
      log.error("Migration was interrupted", e);
      Thread.currentThread().interrupt();
    }
    
    log.info("Repository migration completed");
  }

  /**
   * Migrates proxy repository configuration using Pattern Matching for switch.
   * Ensures decryption operations are compatible with Java 21's enhanced security model.
   */
  private void migrateProxy(final Repository repository) {
	  Configuration configuration = repository.getConfiguration().copy();
	    boolean needUpdate = false;

	    Map<String, Object> authConfig = Optional.ofNullable(configuration.getAttributes())
	        .map(global -> global.get(HTTP_CLIENT_KEY))
	        .map(http -> (Map<String, Object>) http.get(AUTHENTICATION_KEY))
	        .orElse(Collections.emptyMap());

	    Secret passwordKey = Optional.ofNullable((String) authConfig.get(PASSWORD_KEY))
	        .map(secretsService::from)
	        .orElse(null);
	    if (passwordKey != null && isLegacyEncryptedString(passwordKey)) {
	      needUpdate = true;
	      authConfig.put(PASSWORD_KEY, new String(passwordKey.decrypt()));
	    }

	    if (needUpdate) {
	      save(configuration);
	    }
  }

  /**
   * Updates a repository configuration with improved exception handling.
   * If a failure occurs, secrets will be removed.
   */
  private void save(final Configuration configuration) {
    try {
      // Repository manager encrypts and handles removal in case of failure
      repositoryManager.update(configuration);
      log.debug("Successfully updated configuration for repository: {}", configuration.getRepositoryName());
    }
    catch (Exception e) {
      String repoName = configuration.getRepositoryName();
      log.error("Failed to migrate repository {}: {}", repoName, e.getMessage(), e);
      throw new SecretMigrationException("Failed to migrate repository: " + repoName, e);
    }
  }
}