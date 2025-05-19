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
import java.util.Optional;

/**
 * Record representing asset data for efficient pattern matching in Java 21.
 * @since 3.41
 */
record AssetData(String path, String kind, Optional<Component> component, Optional<AssetBlob> blob, 
                 Optional<OffsetDateTime> lastDownloaded, String blobStoreName, long blobSize) {
  /**
   * Creates asset data with the given values.
   */
  public AssetData {
    // Ensure non-null values for pattern matching
    path = path != null ? path : "";
    kind = kind != null ? kind : "";
    component = component != null ? component : Optional.empty();
    blob = blob != null ? blob : Optional.empty();
    lastDownloaded = lastDownloaded != null ? lastDownloaded : Optional.empty();
    blobStoreName = blobStoreName != null ? blobStoreName : "";
  }
}

/**
 * Each asset represents a unique path to binary content in a repository.
 * <p>
 * In Java 21, implementations of this interface can leverage Record Patterns
 * for more concise data handling, especially when working with asset properties
 * and relationships.
 * </p>
 * 
 * <p>Example of using Record Patterns with an Asset implementation:</p>
 * <pre>
 * // Pattern matching in if statement
 * if (asset instanceof Asset asset && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
 *     // Direct access to components without accessor methods
 *     if (path.startsWith("/maven") && component.isPresent()) {
 *         // Process maven component assets
 *     }
 * }
 * 
 * // Pattern matching in switch expression
 * String result = switch (asset.data()) {
 *     case AssetData(var p, "maven-metadata", var c, var b, var d, var s, var z) ->
 *         "Maven metadata at " + p;
 *     case AssetData(var p, "maven-artifact", Optional.of(var c), var b, var d, var s, var z) ->
 *         "Maven artifact for " + c.name() + " at " + p;
 *     default -> "Other asset";
 * };
 * </pre>
 *
 * @since 3.20
 * @see Component
 */
public interface Asset
    extends RepositoryContent
{
  /**
   * The path in the repository.
   */
  String path();

  /**
   * The kind of asset.
   *
   * @since 3.24
   */
  String kind();

  /**
   * Assets may be grouped together under a logical coordinate, represented by a {@link Component}.
   * <p>
   * In Java 21, this Optional can be efficiently handled with pattern matching
   * in combination with the {@link #data()} method.
   * </p>
   */
  Optional<Component> component();

  /**
   * Current blob attached to this asset; proxy repositories may have assets whose blobs have not been fetched yet.
   * If checking for existence please use {@code hasBlob()} which is less expensive.
   * <p>
   * In Java 21, this Optional can be efficiently handled with pattern matching
   * in combination with the {@link #data()} method.
   * </p>
   */
  Optional<AssetBlob> blob();

  /**
   * Indicates whether the asset has a blob, may be faster than {@code blob()} due to lazy loading.
   */
  boolean hasBlob();

  /**
   * If/when this asset was last downloaded.
   * <p>
   * In Java 21, this Optional can be efficiently handled with pattern matching
   * in combination with the {@link #data()} method.
   * </p>
   */
  Optional<OffsetDateTime> lastDownloaded();

  /**
   * Returns the blob store name if blob_store_name is in the query.
   */
  String blobStoreName();

  /**
   * The size of the asset(blob).
   */
  long assetBlobSize();
  
  /**
   * Returns the asset data for pattern matching.
   * <p>
   * This method enables efficient pattern matching with Java 21 Record Patterns.
   * </p>
   * 
   * @return the asset data record
   * @since 3.41
   */
  default AssetData data() {
    return new AssetData(path(), kind(), component(), blob(), lastDownloaded(), blobStoreName(), assetBlobSize());
  }
  
  /**
   * Checks if this asset has the same path as another asset.
   * Leverages pattern matching for efficient comparison in Java 21.
   * 
   * @param other the asset to compare with
   * @return true if paths match
   * @since 3.41
   */
  default boolean hasSamePath(Asset other) {
    if (other instanceof Asset asset && asset.data() instanceof AssetData(var p, var k, var c, var b, var d, var s, var z)) {
      return path().equals(p);
    }
    return false;
  }
  
  /**
   * Checks if this asset belongs to the given component using pattern matching.
   * 
   * @param component the component to check against
   * @return true if the asset belongs to the component
   * @since 3.41
   */
  default boolean belongsTo(Component component) {
    return component().isPresent() && component().get().equals(component);
  }
}
