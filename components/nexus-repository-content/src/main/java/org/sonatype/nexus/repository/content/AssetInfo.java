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

/**
 * Information about asset and it's blob.
 *
 * @since 3.41
 */
public interface AssetInfo
    extends RepositoryContent
{
  /**
   * Record representing asset metadata for use with Java 21 Record Patterns.
   * This allows for more concise and readable code when working with asset metadata.
   * 
   * @since 3.next
   */
  public record AssetMetadata(
      Integer assetId,
      Integer componentId,
      String path,
      String contentType,
      String createdBy,
      String createdByIp,
      OffsetDateTime lastUpdated,
      OffsetDateTime lastDownloaded,
      Long blobSize,
      Map<String, String> checksums,
      OffsetDateTime blobCreated,
      OffsetDateTime addedToRepository
  ) {}

  Integer assetId();

  Integer componentId();

  String path();

  String contentType();

  String createdBy();

  String createdByIp();

  OffsetDateTime lastUpdated();

  OffsetDateTime lastDownloaded();

  Long blobSize();

  Map<String, String> checksums();

  OffsetDateTime blobCreated();

  OffsetDateTime addedToRepository();
  
  /**
   * Returns all asset metadata as a record for use with Java 21 Record Patterns.
   * 
   * <p>Example usage with Record Patterns:</p>
   * <pre>
   * if (asset instanceof AssetInfo info) {
   *   var metadata = info.metadata();
   *   if (metadata instanceof AssetMetadata(var assetId, var componentId, var path, var contentType, 
   *       var createdBy, var createdByIp, var lastUpdated, var lastDownloaded, 
   *       var blobSize, var checksums, var blobCreated, var addedToRepository)) {
   *     // Use the extracted variables directly
   *     System.out.println("Asset path: " + path);
   *     System.out.println("Last updated: " + lastUpdated);
   *   }
   * }
   * </pre>
   * 
   * <p>Or with switch pattern matching:</p>
   * <pre>
   * String result = switch (asset.metadata()) {
   *   case AssetMetadata(_, _, var path, _, _, _, _, _, _, _, _, _) 
   *       when path.endsWith(".jar") -> "JAR file";
   *   case AssetMetadata(_, _, _, _, _, _, _, _, var size, _, _, _) 
   *       when size > 1_000_000 -> "Large file";
   *   default -> "Other file";
   * };
   * </pre>
   * 
   * @return a record containing all asset metadata
   * @since 3.next
   */
  default AssetMetadata metadata() {
    return new AssetMetadata(
        assetId(),
        componentId(),
        path(),
        contentType(),
        createdBy(),
        createdByIp(),
        lastUpdated(),
        lastDownloaded(),
        blobSize(),
        checksums(),
        blobCreated(),
        addedToRepository()
    );
  }
  
  /**
   * Checks if this asset was updated after the specified time.
   * Optimized implementation using Java 21 features.
   *
   * @param time the time to compare against
   * @return true if this asset was updated after the specified time
   * @since 3.next
   */
  default boolean updatedAfter(final OffsetDateTime time) {
    return lastUpdated() != null && lastUpdated().isAfter(time);
  }
  
  /**
   * Checks if this asset was downloaded after the specified time.
   * Optimized implementation using Java 21 features.
   *
   * @param time the time to compare against
   * @return true if this asset was downloaded after the specified time
   * @since 3.next
   */
  default boolean downloadedAfter(final OffsetDateTime time) {
    return lastDownloaded() != null && lastDownloaded().isAfter(time);
  }
  
  /**
   * Gets a checksum value by algorithm name using Java 21 features.
   * 
   * @param algorithm the checksum algorithm name
   * @return the checksum value or null if not available
   * @since 3.next
   */
  default String checksum(final String algorithm) {
    var checksumMap = checksums();
    return checksumMap != null ? checksumMap.get(algorithm) : null;
  }
  
  /**
   * Returns the age of this asset since it was added to the repository.
   * 
   * @return the age in milliseconds, or -1 if the added time is not available
   * @since 3.next
   */
  default long ageInRepository() {
    var added = addedToRepository();
    return added != null ? 
      System.currentTimeMillis() - added.toInstant().toEpochMilli() : -1;
  }
}