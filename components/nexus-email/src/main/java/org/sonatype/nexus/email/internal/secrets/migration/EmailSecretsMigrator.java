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
package org.sonatype.nexus.email.internal.secrets.migration;

import java.time.Duration;
import java.time.Instant;

import javax.inject.Inject;
import javax.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.security.secrets.SecretsMigrator;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migrates email password secrets to the centralized secrets storage.
 * <p>
 * This component is compatible with Virtual Thread execution and can be safely
 * executed on a virtual thread without blocking platform threads during I/O operations.
 * </p>
 */
@Named
public class EmailSecretsMigrator
    implements SecretsMigrator
{
  private static final Logger log = LoggerFactory.getLogger(EmailSecretsMigrator.class);
  
  private final EmailManager emailManager;

  @Inject
  public EmailSecretsMigrator(final EmailManager emailManager) {
    this.emailManager = checkNotNull(emailManager);
  }

  /**
   * Migrates email password secrets to the centralized secrets storage.
   * <p>
   * This method is compatible with Virtual Thread execution and can be safely
   * executed on a virtual thread without blocking platform threads during I/O operations.
   * </p>
   */
  @Override
  public void migrate() {
    Instant startTime = Instant.now();
    log.debug(STR."Starting email password secret migration at \{startTime}");
    
    try {
      CancelableHelper.checkCancellation();
      EmailConfiguration configuration = emailManager.getConfiguration();
      Secret password = configuration.getPassword();

      // Use pattern matching to handle different secret states
      if (password instanceof Secret s) {
        if (isPersistedSecret(s)) {
          log.debug(STR."Email password secret already migrated, skipping migration");
        } else {
          log.info(STR."Migrating email password secret to centralized storage");
          try {
            // email manager encrypts and handles removal in case of failure
            emailManager.setConfiguration(configuration, new String(s.decrypt()));
            log.info(STR."Successfully migrated email password secret");
          } catch (Exception e) {
            log.error(STR."Failed to migrate email password secret: \{e.getMessage()}", e);
            throw e;
          }
        }
      } else {
        log.debug(STR."No email password secret found, skipping migration");
      }
    } catch (Exception e) {
      log.error(STR."Error during email password secret migration: \{e.getMessage()}", e);
      throw e;
    } finally {
      Instant endTime = Instant.now();
      Duration duration = Duration.between(startTime, endTime);
      log.debug(STR."Email password secret migration completed in \{duration.toMillis()} ms");
    }
  }
  
  /**
   * Checks if the given secret is already persisted in the centralized storage.
   * 
   * @param secret the secret to check
   * @return true if the secret is already persisted, false otherwise
   */
  private boolean isPersistedSecret(final Secret secret) {
    try {
      // If the secret has a valid ID, it's already persisted in the centralized storage
      return secret.getId() != null && !secret.getId().isEmpty();
    }
    catch (UnsupportedOperationException e) {
      // If getId() throws UnsupportedOperationException, it's not a persisted secret
      log.debug(STR."Secret does not support getId(), assuming not persisted: \{e.getMessage()}");
      return false;
    }
  }
}