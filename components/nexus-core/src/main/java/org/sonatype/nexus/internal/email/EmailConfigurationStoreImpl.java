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
package org.sonatype.nexus.internal.email;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.transaction.Transactional;

/**
 * MyBatis {@link EmailConfigurationStore} implementation with Java 21 enhancements.
 * <p>
 * Uses virtual threads for database operations and pattern matching for transaction handling.
 *
 * @since 3.21
 */
@Named("mybatis")
@Singleton
public class EmailConfigurationStoreImpl
    extends ConfigStoreSupport<EmailConfigurationDAO>
    implements EmailConfigurationStore
{
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public EmailConfigurationStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
    // Create a virtual thread per task executor for I/O-bound database operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public EmailConfiguration newConfiguration() {
    return new EmailConfigurationData();
  }

  /**
   * Loads email configuration using virtual threads for improved I/O performance.
   * 
   * @return the email configuration or null if not found
   */
  @Transactional
  @Override
  public EmailConfiguration load() {
    // Use virtual thread for database operation
    try {
      return virtualThreadExecutor.submit(() -> dao().get().orElse(null)).get();
    } catch (Exception e) {
      log.error(STR."Error loading email configuration: \{e.getMessage()}", e);
      return null;
    }
  }

  /**
   * Saves email configuration using virtual threads for improved I/O performance.
   * Uses pattern matching to handle different configuration types.
   *
   * @param configuration the email configuration to save
   */
  @Transactional
  @Override
  public void save(final EmailConfiguration configuration) {
    try {
      // Use pattern matching for switch to handle different configuration types
      switch (configuration) {
        case EmailConfigurationData data -> {
          // Post event using thread-safe mechanism
          postCommitEvent(() -> new EmailConfigurationChanged());
          // Use virtual thread for database operation
          virtualThreadExecutor.submit(() -> dao().set(data)).get();
        }
        case null -> throw new IllegalArgumentException("Configuration cannot be null");
        default -> {
          // Handle other EmailConfiguration implementations by converting to EmailConfigurationData
          log.warn(STR."Converting non-standard configuration type \{configuration.getClass().getName()} to EmailConfigurationData");
          EmailConfigurationData data = new EmailConfigurationData();
          data.setEnabled(configuration.isEnabled());
          data.setHost(configuration.getHost());
          data.setPort(configuration.getPort());
          data.setUsername(configuration.getUsername());
          data.setPassword(configuration.getPassword());
          data.setFromAddress(configuration.getFromAddress());
          data.setSubjectPrefix(configuration.getSubjectPrefix());
          data.setStartTlsEnabled(configuration.isStartTlsEnabled());
          data.setStartTlsRequired(configuration.isStartTlsRequired());
          data.setSslOnConnectEnabled(configuration.isSslOnConnectEnabled());
          data.setSslCheckServerIdentityEnabled(configuration.isSslCheckServerIdentityEnabled());
          data.setNexusTrustStoreEnabled(configuration.isNexusTrustStoreEnabled());
          
          // Post event using thread-safe mechanism
          postCommitEvent(() -> new EmailConfigurationChanged());
          // Use virtual thread for database operation
          virtualThreadExecutor.submit(() -> dao().set(data)).get();
        }
      }
    } catch (Exception e) {
      log.error(STR."Error saving email configuration: \{e.getMessage()}", e);
      throw new RuntimeException("Failed to save email configuration", e);
    }
  }
}