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
package org.sonatype.nexus.repository.content.fluent;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;

import com.google.common.hash.HashCode;

/**
 * Fluent API for ingesting blobs.
 * 
 * <p>Implementations of this interface should leverage Java 21 Virtual Threads for I/O-bound operations
 * to improve performance and scalability. Virtual Threads are particularly well-suited for blob ingestion
 * operations which typically involve significant I/O operations.</p>
 *
 * @since 3.21
 */
public interface FluentBlobs
{
  /**
   * Ingests the given stream as a temporary blob with the requested hashing.
   * 
   * <p>This method performs I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   */
  TempBlob ingest(InputStream in, @Nullable String contentType, Iterable<HashAlgorithm> hashing);

  /**
   * Ingests the given stream as a temporary blob with custom headers and the requested hashing.
   * 
   * <p>This method performs I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   */
  TempBlob ingest(InputStream in, @Nullable String contentType, Map<String, String> headers, Iterable<HashAlgorithm> hashing);

  /**
   * Ingests the given payload as a temporary blob with the requested hashing.
   * 
   * <p>This method performs I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   */
  TempBlob ingest(Payload payload, Iterable<HashAlgorithm> hashing);

  /**
   * Ingests the given blob from a source blob store with the provided hashes.
   * 
   * <p>This method performs I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   */
  TempBlob ingest(final Blob srcBlob, final BlobStore srcStore, final Map<HashAlgorithm, HashCode> hashes);


  /**
   * Ingests the given payload as a temporary blob with the requested hashing.
   *
   * <p>This method performs file I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   *
   * @param path        the path to the file on the local file system
   * @param contentType the content type of the provided file if known
   * @param hashing     the hashing algorithms to use for the blob
   * @param requireHardLink  when true ingest will fail if the attempt to hard link fails, otherwise an attempt will be
   *                         made to copy the file content.
   *
   * @since 3.41
   */
  TempBlob ingest(Path path, @Nullable String contentType, Iterable<HashAlgorithm> hashing, boolean requireHardLink);

  /**
   * Ingests a blob from a {@code sourceFile} via hard-linking.
   * 
   * <p>This method performs file I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   *
   * @since 3.29
   */
  Blob ingest(Path sourceFile, Map<String, String> headers, HashCode sha1, long size);

  /**
   * Fetches the Blob associated with the specified BlobRef or Optional.empty() if
   * no such Blob exists.
   * 
   * <p>This method performs I/O operations and is suitable for execution on a Virtual Thread
   * to avoid blocking platform threads during I/O operations.</p>
   *
   * @since 3.29
   */
  Optional<Blob> blob(BlobRef blobRef);

  /**
   * Gets the metrics for the blob store.
   * 
   * <p>This method may perform I/O operations depending on the implementation and is suitable 
   * for execution on a Virtual Thread when it involves I/O operations.</p>
   *
   * @since 3.29
   */
  BlobStoreMetrics getMetrics();
}