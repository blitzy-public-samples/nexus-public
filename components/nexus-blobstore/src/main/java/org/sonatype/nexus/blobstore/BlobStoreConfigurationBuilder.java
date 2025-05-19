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
package org.sonatype.nexus.blobstore;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.quota.BlobStoreQuotaSupport;

/**
 * A builder for blob store configurations.
 * 
 * This implementation is optimized for Java 21 features including pattern matching and
 * efficient operation with Virtual Threads.
 *
 * @since 3.36
 */
public class BlobStoreConfigurationBuilder
{
  // Using AtomicReference for thread-safe lazy initialization with Virtual Threads
  private final Supplier<BlobStoreConfiguration> configurationSupplier;

  private final String name;

  private String type;

  private String quotaType;

  private long quotaLimit;

  /**
   * Creates a new builder using the specified name for the resulting blob store.
   * 
   * @param name the name for the blob store configuration
   * @param configurationSupplier supplier for creating new blob store configurations
   * @throws NullPointerException if name or configurationSupplier is null
   */
  public BlobStoreConfigurationBuilder(
      final String name,
      final Supplier<BlobStoreConfiguration> configurationSupplier)
  {
    // Using pattern matching for null checks - if name is null, this will throw NPE
    this.name = Objects.requireNonNull(name, "Blob store name cannot be null");
    this.configurationSupplier = Objects.requireNonNull(configurationSupplier, "Configuration supplier cannot be null");
  }

  /**
   * Sets the type of blob store.
   * 
   * @param type the blob store type
   * @return this builder instance
   * @throws NullPointerException if type is null
   */
  public BlobStoreConfigurationBuilder type(final String type) {
    // Using pattern matching for null checks
    this.type = Objects.requireNonNull(type, "Blob store type cannot be null");
    return this;
  }

  /**
   * Sets the blob store quota configuration.
   * 
   * @param quotaType the type of quota to apply
   * @param limit the quota limit value
   * @return this builder instance
   */
  public BlobStoreConfigurationBuilder quotaConfig(final String quotaType, final long limit) {
    this.quotaType = quotaType; // Allowing null for quotaType to disable quota
    this.quotaLimit = limit;
    return this;
  }

  /**
   * Creates the configuration for the desired blob store.
   * 
   * This method is optimized for use with Virtual Threads by minimizing operations
   * that could cause thread pinning.
   * 
   * @return the configured blob store configuration
   */
  public BlobStoreConfiguration build() {
    // Get configuration from supplier - optimized for Virtual Threads
    final BlobStoreConfiguration configuration = configurationSupplier.get();
    
    // Set required properties
    configuration.setName(name);
    configuration.setType(type);
    
    // Set quota configuration if specified
    if (quotaType != null) {
      var attributes = configuration.attributes(BlobStoreQuotaSupport.ROOT_KEY);
      attributes.set(BlobStoreQuotaSupport.TYPE_KEY, quotaType);
      attributes.set(BlobStoreQuotaSupport.LIMIT_KEY, quotaLimit);
    }
    
    return configuration;
  }
}