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
package org.sonatype.nexus.repository.content.event.asset;

import java.util.Optional;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetData;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.content.store.InternalIds.contentRepositoryId;

/**
 * Base {@link Asset} event.
 * <p>
 * This class has been updated for Java 21 to leverage Record Patterns for improved data encapsulation,
 * ensure thread safety for compatibility with Virtual Threads, and use String Templates for better
 * diagnostic output.
 * </p>
 *
 * @since 3.26
 */
public class AssetEvent
    extends ContentStoreEvent
{
  private final Asset asset;

  /**
   * Creates a new asset event with the given asset.
   * <p>
   * This constructor ensures immutability for thread safety, which is essential
   * when working with Virtual Threads in Java 21.
   * </p>
   *
   * @param asset the asset that triggered this event (must not be null)
   */
  protected AssetEvent(final Asset asset) {
    super(contentRepositoryId(asset));
    this.asset = checkNotNull(asset);
  }

  /**
   * Gets the asset that triggered this event.
   *
   * @return the immutable asset instance
   */
  public Asset getAsset() {
    return asset;
  }
  
  /**
   * Extracts the asset path using Java 21 Record Patterns.
   * <p>
   * This demonstrates how to leverage Record Patterns for more concise data access.
   * </p>
   *
   * @return the asset path or empty string if pattern matching fails
   */
  public String getAssetPath() {
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return path;
    }
    return "";
  }
  
  /**
   * Checks if this asset event is for a component-related asset using Record Patterns.
   *
   * @return true if the asset has an associated component
   */
  public boolean hasComponent() {
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, Optional.of(var component), var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return true;
    }
    return false;
  }

  @Override
  public String toString() {
    return STR."AssetEvent{asset=\{asset}} \{super.toString()}";
  }
}
