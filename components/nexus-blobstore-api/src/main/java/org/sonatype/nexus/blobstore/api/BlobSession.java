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
package org.sonatype.nexus.blobstore.api;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import javax.annotation.Nullable;

import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.TransactionalSession;

import com.google.common.hash.HashCode;

/**
 * Represents a session with a {@link BlobStore}.
 * <p>
 * This interface provides transactional access to blob operations, ensuring data consistency
 * across multiple blob operations. All methods in this interface are thread-safe and can be
 * safely called from multiple threads concurrently.
 * <p>
 * When using with Java 21 Virtual Threads:
 * <ul>
 *   <li>I/O-bound methods are annotated with {@link VirtualThreadFriendly} to indicate they
 *       are optimized for execution within Virtual Threads</li>
 *   <li>Implementations should ensure that I/O operations do not cause thread pinning</li>
 *   <li>Transactional boundaries should be kept as short as possible to minimize resource contention</li>
 *   <li>Care should be taken to avoid blocking operations that could pin carrier threads</li>
 * </ul>
 *
 * @since 3.20
 */
public interface BlobSession<T extends Transaction>
    extends TransactionalSession<T>
{
  /**
   * @see BlobStore#create(InputStream, Map)
   */
  @VirtualThreadFriendly
  default Blob create(InputStream blobData, Map<String, String> headers) {
    return create(blobData, headers, null);
  }

  /**
   * Creates a new blob with the given content, headers, and optional blob ID.
   * <p>
   * This method is optimized for execution within Virtual Threads and uses non-blocking I/O
   * operations where possible to avoid carrier thread pinning.
   *
   * @see BlobStore#create(InputStream, Map, BlobId)
   */
  @VirtualThreadFriendly
  Blob create(InputStream blobData, Map<String, String> headers, @Nullable BlobId blobId);

  /**
   * Creates a new blob from a source file with the given headers, size, and SHA-1 hash.
   * <p>
   * This method is optimized for execution within Virtual Threads and uses non-blocking I/O
   * operations where possible to avoid carrier thread pinning.
   *
   * @see BlobStore#create(Path, Map, long, HashCode)
   */
  @VirtualThreadFriendly
  Blob create(Path sourceFile, Map<String, String> headers, long size, HashCode sha1);

  /**
   * Creates a copy of an existing blob with new headers.
   * <p>
   * This method is optimized for execution within Virtual Threads and uses non-blocking I/O
   * operations where possible to avoid carrier thread pinning.
   *
   * @see BlobStore#copy(BlobId, Map)
   */
  @VirtualThreadFriendly
  Blob copy(BlobId blobId, Map<String, String> headers);

  /**
   * @see BlobStore#get(BlobId)
   */
  @Nullable
  @VirtualThreadFriendly
  default Blob get(BlobId blobId) {
    return get(blobId, false);
  }

  /**
   * Retrieves a blob by its ID, optionally including deleted blobs.
   * <p>
   * This method is optimized for execution within Virtual Threads and uses non-blocking I/O
   * operations where possible to avoid carrier thread pinning.
   *
   * @see BlobStore#get(BlobId, boolean)
   */
  @Nullable
  @VirtualThreadFriendly
  Blob get(BlobId blobId, boolean includeDeleted);

  /**
   * Checks if a blob with the given ID exists.
   * <p>
   * This method is optimized for execution within Virtual Threads and uses non-blocking I/O
   * operations where possible to avoid carrier thread pinning.
   *
   * @see BlobStore#exists(BlobId)
   */
  @VirtualThreadFriendly
  boolean exists(BlobId blobId);

  /**
   * Deletes a blob with the given ID.
   * <p>
   * Note: While this method is not marked as {@link VirtualThreadFriendly}, implementations
   * should still strive to minimize blocking operations that could cause thread pinning.
   *
   * @see BlobStore#delete(BlobId, String)
   */
  boolean delete(BlobId blobId);
}