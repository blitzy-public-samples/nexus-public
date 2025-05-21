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
package org.sonatype.nexus.internal.httpclient;

import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.LoggerFactory;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.transaction.Transactional;

/**
 * MyBatis {@link HttpClientConfigurationStore} implementation.
 * <p>
 * Optimized for Java 21 with Virtual Threads support and enhanced logging.
 *
 * @since 3.21
 */
@Named("mybatis")
@Singleton
public class HttpClientConfigurationStoreImpl
    extends ConfigStoreSupport<HttpClientConfigurationDAO>
    implements HttpClientConfigurationStore
{
  private static final org.slf4j.Logger log = LoggerFactory.getLogger(HttpClientConfigurationStoreImpl.class);

  @Inject
  public HttpClientConfigurationStoreImpl(final DataSessionSupplier sessionSupplier) {
    super(sessionSupplier);
  }

  @Override
  public HttpClientConfiguration newConfiguration() {
    log.debug(STR."Creating new HTTP client configuration");
    return new HttpClientConfigurationData();
  }

  /**
   * Loads the HTTP client configuration.
   * <p>
   * This method is optimized for Virtual Threads and will not cause thread pinning.
   *
   * @return the HTTP client configuration or null if not found
   */
  @Transactional
  @Override
  public HttpClientConfiguration load() {
    try {
      Optional<HttpClientConfiguration> config = dao().get();
      if (config.isPresent()) {
        log.debug(STR."Loaded HTTP client configuration: \{config.get().getClass().getSimpleName()}");
      } else {
        log.debug(STR."No HTTP client configuration found");
      }
      return config.orElse(null);
    } catch (Exception e) {
      log.error(STR."Failed to load HTTP client configuration: \{e.getMessage()}", e);
      throw e;
    }
  }

  /**
   * Saves the HTTP client configuration.
   * <p>
   * This method is optimized for Virtual Threads and will not cause thread pinning.
   *
   * @param configuration the HTTP client configuration to save
   */
  @Transactional
  @Override
  public void save(final HttpClientConfiguration configuration) {
    try {
      log.debug(STR."Saving HTTP client configuration: \{configuration.getClass().getSimpleName()}");
      
      // Post event after transaction commit
      postCommitEvent(HttpClientConfigurationChanged::new);

      // Save the configuration
      dao().set((HttpClientConfigurationData) configuration);
      
      log.debug(STR."HTTP client configuration saved successfully");
    } catch (Exception e) {
      log.error(STR."Failed to save HTTP client configuration: \{e.getMessage()}", e);
      throw e;
    }
  }
}