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
package org.sonatype.nexus.coreui.internal.blobstore;

import java.util.Objects;
import javax.annotation.Nullable;

import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;

/**
 * Response object for blob store UI data.
 * 
 * @since 3.30
 */
public record BlobStoreUIResponse(
    String name,
    String typeId,
    String typeName,
    String path,
    boolean unavailable,
    long blobCount,
    long totalSizeInBytes,
    long availableSpaceInBytes,
    boolean unlimited)
{
  /**
   * Creates a new BlobStoreUIResponse from the given configuration and metrics.
   *
   * @param typeId the blob store type ID
   * @param configuration the blob store configuration
   * @param metrics the blob store metrics, may be null if unavailable
   * @param path the blob store path
   */
  public BlobStoreUIResponse(
      final String typeId,
      final BlobStoreConfiguration configuration,
      @Nullable final BlobStoreMetrics metrics,
      final String path)
  {
    this(
        Objects.requireNonNull(configuration.getName()),
        Objects.requireNonNull(typeId),
        configuration.getType(),
        Objects.requireNonNull(path),
        metrics == null || metrics.isUnavailable(),
        metrics != null ? metrics.getBlobCount() : 0,
        metrics != null ? metrics.getTotalSize() : 0,
        metrics != null ? metrics.getAvailableSpace() : 0,
        metrics != null && metrics.isUnlimited()
    );
  }
}