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
package org.sonatype.nexus.repository.blobstore;

import java.util.List;
import java.util.Optional;

import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

/**
 * {@link BlobStoreConfiguration} store.
 *
 * Implementations of this interface should leverage Java 21 Virtual Threads for I/O-bound operations
 * to improve scalability and performance. Virtual Threads are particularly well-suited for database
 * operations, file system access, and other persistence-related tasks that are common in BlobStore
 * configuration management.
 *
 * since 3.0
 */
public interface BlobStoreConfigurationStore
    extends Lifecycle
{
  /**
   * Retrieves all BlobStoreConfigurations.
   * 
   * Implementations should leverage Virtual Threads for this potentially I/O-intensive operation
   * to improve performance under high concurrency scenarios.
   *
   * @return all BlobStoreConfigurations
   */
  List<BlobStoreConfiguration> list();

  /**
   * Persist a new BlobStoreConfiguration.
   * 
   * Implementations should use Virtual Threads for this I/O-bound operation to ensure
   * efficient resource utilization and improved throughput, especially when handling
   * multiple concurrent configuration creations.
   */
  void create(BlobStoreConfiguration configuration);

  /**
   * Update an existing BlobStoreConfiguration.
   * 
   * Implementations should leverage Virtual Threads for this persistence operation
   * to optimize performance while maintaining backward compatibility with existing code.
   *
   * @since 3.14
   */
  void update(BlobStoreConfiguration configuration);

  /**
   * Delete an existing BlobStoreConfiguration.
   * 
   * Implementations should utilize Virtual Threads for this I/O-bound operation
   * to improve system responsiveness during configuration cleanup operations.
   */
  void delete(BlobStoreConfiguration configuration);

  /**
   * Find a BlobStoreConfiguration by name.
   * 
   * Implementations should leverage Virtual Threads for this potentially I/O-intensive lookup
   * to ensure efficient resource utilization, especially under high concurrency scenarios.
   *
   * @since 3.14
   */
  BlobStoreConfiguration read(String name);

  /**
   * Find the parent group of a blob store
   * 
   * Implementations should use Virtual Threads for this query operation to optimize
   * performance while maintaining backward compatibility with existing code.
   *
   * @param name of the child to search on
   * @return the {@link Optional<BlobStoreConfiguration>} for the parent group if it exists
   *
   * @since 3.15
   */
  Optional<BlobStoreConfiguration> findParent(String name);

  /**
   * Create a new empty {@link BlobStoreConfiguration} suitable for use with this store
   * 
   * This method typically doesn't involve I/O operations but is included for interface completeness.
   * Implementations should ensure this method remains lightweight and efficient.
   *
   * @since 3.20
   */
  BlobStoreConfiguration newConfiguration();
}