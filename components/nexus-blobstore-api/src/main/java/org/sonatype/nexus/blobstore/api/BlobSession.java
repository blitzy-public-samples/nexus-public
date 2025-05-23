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
 *
 * <p>This interface provides transactional access to blob storage operations. Methods marked with
 * {@link VirtualThreadFriendly} are designed to be safely executed within Java 21 Virtual Threads,
 * which is particularly beneficial for I/O-bound operations like blob creation, retrieval, and existence checks.</p>
 *
 * <p><strong>Thread Safety:</strong> Implementations must ensure thread safety for all operations.
 * When using Virtual Threads with transactional operations, implementations should avoid operations
 * that could cause thread pinning (like synchronized blocks on objects that might be contended).</p>
 *
 * <p><strong>Transactional Considerations:</strong> When implementing transactional operations with Virtual Threads:
 * <ul>
 *   <li>Avoid blocking operations within transactions that could pin carrier threads</li>
 *   <li>Keep transactions as short as possible to minimize resource contention</li>
 *   <li>Consider using non-blocking I/O operations where available</li>
 *   <li>Be aware that many Virtual Threads may execute concurrently against the same BlobStore</li>
 *   <li>Ensure proper isolation levels are maintained across Virtual Thread boundaries</li>
 * </ul></p>
 *
 * @since 3.20
 */
public interface BlobSession<T extends Transaction>
    extends TransactionalSession<T>
{
  /**
   * @see BlobStore#create(InputStream, Map)
   */
  default Blob create(InputStream blobData, Map<String, String> headers) {
    return create(blobData, headers, null);
  }

  /**
   * Creates a new Blob with the given content and headers.
   *
   * <p>This is an I/O-bound operation suitable for execution in Virtual Threads.</p>
   *
   * @see BlobStore#create(InputStream, Map, BlobId)
   */
  @VirtualThreadFriendly
  Blob create(InputStream blobData, Map<String, String> headers, @Nullable BlobId blobId);

  /**
   * Creates a new Blob from a source file with the given headers, size, and hash.
   *
   * <p>This is an I/O-bound operation suitable for execution in Virtual Threads.</p>
   *
   * @see BlobStore#create(Path, Map, long, HashCode)
   */
  @VirtualThreadFriendly
  Blob create(Path sourceFile, Map<String, String> headers, long size, HashCode sha1);

  /**
   * Creates a copy of an existing Blob with new headers.
   *
   * <p>This is an I/O-bound operation suitable for execution in Virtual Threads.</p>
   *
   * @see BlobStore#copy(BlobId, Map)
   */
  @VirtualThreadFriendly
  Blob copy(BlobId blobId, Map<String, String> headers);

  /**
   * @see BlobStore#get(BlobId)
   */
  @Nullable
  default Blob get(BlobId blobId) {
    return get(blobId, false);
  }

  /**
   * Retrieves a Blob by its ID, optionally including deleted Blobs.
   *
   * <p>This is an I/O-bound operation suitable for execution in Virtual Threads.</p>
   *
   * @see BlobStore#get(BlobId, boolean)
   */
  @Nullable
  @VirtualThreadFriendly
  Blob get(BlobId blobId, boolean includeDeleted);

  /**
   * Checks if a Blob exists by its ID.
   *
   * <p>This is an I/O-bound operation suitable for execution in Virtual Threads.</p>
   *
   * @see BlobStore#exists(BlobId)
   */
  @VirtualThreadFriendly
  boolean exists(BlobId blobId);

  /**
   * Deletes a Blob by its ID.
   *
   * @see BlobStore#delete(BlobId, String)
   */
  boolean delete(BlobId blobId);
}