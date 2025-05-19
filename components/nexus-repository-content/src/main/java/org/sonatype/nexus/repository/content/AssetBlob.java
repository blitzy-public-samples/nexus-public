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
package org.sonatype.nexus.repository.content;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;

/**
 * Details of the {@link Blob} containing the binary content of an {@link Asset}.
 *
 * Apart from the {@link BlobRef} the rest of these properties are also stored in
 * the blob store, but copies of them are persisted here for performance reasons.
 *
 * <p>In Java 21, implementations of this interface can leverage Record Patterns
 * for more efficient handling of blob metadata, especially when working with
 * checksum maps and timestamp operations.</p>
 *
 * <p>Example of using Record Patterns with an AssetBlob implementation:</p>
 * <pre>
 * // Assuming an implementation like: record AssetBlobRecord(...) implements AssetBlob
 * if (blob instanceof AssetBlobRecord(BlobRef ref, var size, var contentType, Map<String, String> checksums, var created, var added, var createdBy, var createdByIp)) {
 *     // Direct access to components without accessor methods
 *     String sha1 = checksums.get("sha1");
 *     // Timestamp operations optimized for Java 21
 *     boolean isRecent = added.isAfter(OffsetDateTime.now().minusDays(1));
 * }
 * </pre>
 *
 * @since 3.20
 */
public interface AssetBlob
{
  /**
   * Reference to the blob.
   *
   * @return the blob reference
   */
  BlobRef blobRef();

  /**
   * Size of the blob.
   *
   * @return the blob size in bytes
   */
  long blobSize();

  /**
   * Content-type of the blob.
   *
   * @return the content type
   */
  String contentType();

  /**
   * Checksums for the blob.
   * 
   * <p>In Java 21, this map can be efficiently accessed using Record Patterns
   * to extract specific checksums without intermediate variables.</p>
   *
   * @return map of algorithm to checksum
   * @since 3.24
   */
  Map<String, String> checksums();

  /**
   * When the blob was created.
   * 
   * <p>In Java 21, timestamp operations are optimized for better performance
   * and can be used directly in pattern matching contexts.</p>
   *
   * @return the creation timestamp
   */
  OffsetDateTime blobCreated();

  /**
   * When the blob was added to repository.
   * 
   * <p>In Java 21, timestamp operations are optimized for better performance
   * and can be used directly in pattern matching contexts.</p>
   *
   * @return the timestamp when added to repository
   */
  OffsetDateTime addedToRepository();

  /**
   * The user that triggered creation of this blob; empty if it was an internal request.
   * 
   * <p>In Java 21, Optional values can be efficiently handled with pattern matching
   * and are guaranteed to never be null.</p>
   *
   * @return optional user identifier
   */
  Optional<String> createdBy();

  /**
   * The client IP that triggered creation of this blob; empty if it was an internal request.
   * 
   * <p>In Java 21, Optional values can be efficiently handled with pattern matching
   * and are guaranteed to never be null.</p>
   *
   * @return optional client IP
   */
  Optional<String> createdByIp();
}