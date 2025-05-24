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

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.crypto.secrets.Secret;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.scheduling.CancelableHelper;
import org.sonatype.nexus.security.secrets.SecretMigrationException;
import org.sonatype.nexus.security.secrets.SecretsMigrator;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migrates email password secrets from legacy format to the centralized secrets vault.
 * <p>
 * This migrator is compatible with Virtual Threads and can be executed in a non-blocking manner.
 *
 * @since 3.0
 */
@Named
public class EmailSecretsMigrator
    extends ComponentSupport
    implements SecretsMigrator
{
  private final EmailManager emailManager;

  @Inject
  public EmailSecretsMigrator(final EmailManager emailManager) {
    this.emailManager = checkNotNull(emailManager);
  }

  /**
   * Migrates email password secrets to the centralized secrets vault.
   * <p>
   * This method is compatible with Virtual Threads and can be safely executed in a non-blocking manner.
   * It handles different secret states using pattern matching and provides structured logging for better diagnostics.
   */
  @Override
  public void migrate() {
    long startTime = System.currentTimeMillis();
    
    try {
      // Check if the migration task has been canceled
      CancelableHelper.checkCancellation();
      
      // Retrieve the current email configuration
      EmailConfiguration configuration = emailManager.getConfiguration();
      Secret password = configuration.getPassword();
      
      // Use pattern matching to handle different secret states
      switch (password) {
        case null -> {
          // No password to migrate
          log("No email password found for migration");
        }
        case Secret s when isPersistedSecret(s) -> {
          // Password is already migrated
          log("Email password is already migrated with ID: %s".formatted(s.getId()));
        }
        case Secret s -> {
          // Password needs migration
          log("Migrating email password to centralized secrets vault");
          try {
            // Decrypt the password and migrate it using the email manager
            String decryptedPassword = new String(s.decrypt());
            emailManager.setConfiguration(configuration, decryptedPassword);
            log("Successfully migrated email password to centralized secrets vault");
          } 
          catch (Exception e) {
            // Enhanced error handling with structured logging
            String errorMessage = "Failed to migrate email password: %s".formatted(e.getMessage());
            log.error(STR."\{errorMessage}", e);
            throw new SecretMigrationException(errorMessage, e);
          }
        }
      }
    } 
    finally {
      // Log metrics for migration performance tracking
      long duration = System.currentTimeMillis() - startTime;
      log("Email password migration completed in %d ms".formatted(duration));
    }
  }
  
  /**
   * Logs a message using Java 21 String Templates for structured logging.
   * 
   * @param message the message to log
   */
  private void log(String message) {
    log.info(STR."Email password migration: \{message}");
  }
}