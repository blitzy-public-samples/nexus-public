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
package org.sonatype.nexus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.script.ScriptApi;

/**
 * BlobStore provisioning capabilities of the repository manager.
 * 
 * <p>This interface is designed to be compatible with Java 21 Virtual Threads. Implementations
 * should leverage Virtual Threads for I/O-bound operations to improve throughput and scalability.
 * Virtual Threads are particularly well-suited for BlobStore operations as they typically involve
 * significant I/O operations that would otherwise block platform threads.</p>
 *
 * @since 3.0
 */
public interface BlobStoreApi
    extends ScriptApi
{
  default String getName() {
    return "blobStore";
  }

  /**
   * Create a new File based BlobStore.
   * 
   * <p>This operation is I/O-bound and implementations should consider using Virtual Threads
   * for improved throughput when handling multiple concurrent creation requests.</p>
   * 
   * @param name the name for the new BlobStore
   * @param path the path where the BlobStore should store data
   * @return the configuration for the created BlobStore
   */
  BlobStoreConfiguration createFileBlobStore(String name, String path);

  /**
   * Create a new BlobStore group.
   *
   * <p>This operation involves coordination across multiple BlobStores and implementations
   * should consider using Virtual Threads to handle the potential I/O operations efficiently.</p>
   *
   * @param name the name for the new BlobStore
   * @param memberNames name of the member BlobStores
   * @param fillPolicy name of the fill policy
   * @return the configuration for the created BlobStore group
   * @since 3.14
   */
  BlobStoreConfiguration createBlobStoreGroup(String name, List<String> memberNames, String fillPolicy);

  /**
   * Create a new S3 based BlobStore.
   *
   * <p>This operation involves network I/O and is particularly well-suited for Virtual Threads.
   * Implementations should avoid using synchronized blocks or methods when performing S3 operations
   * to prevent Virtual Thread pinning, which can reduce performance benefits.</p>
   *
   * @param name the name for the new BlobStore
   * @param config the configuration map for the new blobstore
   * @return the configuration for the created S3 BlobStore
   * @since 3.6
   */
  BlobStoreConfiguration createS3BlobStore(String name, Map<String, String> config);
  
  /**
   * Asynchronously create a new File based BlobStore using Virtual Threads.
   * 
   * <p>This method provides an asynchronous API that leverages Java 21 Virtual Threads
   * for non-blocking I/O operations. This is particularly useful for high-throughput scenarios
   * where many BlobStores need to be created concurrently.</p>
   * 
   * @param name the name for the new BlobStore
   * @param path the path where the BlobStore should store data
   * @return a CompletableFuture that will complete with the BlobStore configuration when creation is finished
   * @since 3.60
   */
  default CompletableFuture<BlobStoreConfiguration> createFileBlobStoreAsync(String name, String path) {
    return CompletableFuture.supplyAsync(() -> createFileBlobStore(name, path));
  }

  /**
   * Asynchronously create a new BlobStore group using Virtual Threads.
   *
   * <p>This method provides an asynchronous API that leverages Java 21 Virtual Threads
   * for non-blocking operations when creating BlobStore groups.</p>
   *
   * @param name the name for the new BlobStore
   * @param memberNames name of the member BlobStores
   * @param fillPolicy name of the fill policy
   * @return a CompletableFuture that will complete with the BlobStore group configuration when creation is finished
   * @since 3.60
   */
  default CompletableFuture<BlobStoreConfiguration> createBlobStoreGroupAsync(String name, List<String> memberNames, String fillPolicy) {
    return CompletableFuture.supplyAsync(() -> createBlobStoreGroup(name, memberNames, fillPolicy));
  }

  /**
   * Asynchronously create a new S3 based BlobStore using Virtual Threads.
   *
   * <p>This method provides an asynchronous API that leverages Java 21 Virtual Threads
   * for non-blocking network I/O operations when interacting with S3. This approach is
   * particularly effective for cloud storage operations that may have variable latency.</p>
   *
   * @param name the name for the new BlobStore
   * @param config the configuration map for the new blobstore
   * @return a CompletableFuture that will complete with the S3 BlobStore configuration when creation is finished
   * @since 3.60
   */
  default CompletableFuture<BlobStoreConfiguration> createS3BlobStoreAsync(String name, Map<String, String> config) {
    return CompletableFuture.supplyAsync(() -> createS3BlobStore(name, config));
  }
}