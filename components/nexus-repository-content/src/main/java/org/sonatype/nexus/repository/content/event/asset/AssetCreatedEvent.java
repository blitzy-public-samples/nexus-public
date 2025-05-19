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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event sent whenever an {@link Asset} is created.
 *
 * @since 3.26
 */
public class AssetCreatedEvent
    extends AssetEvent
{
  /**
   * Creates a new asset created event.
   *
   * @param asset the created asset (must not be null)
   */
  public AssetCreatedEvent(final Asset asset) {
    super(checkNotNull(asset, "Asset cannot be null"));
  }

  /**
   * Extracts path information from the asset using Java 21 Record Patterns.
   * 
   * @return the asset path or empty string if pattern matching fails
   */
  public String getAssetPath() {
    Asset asset = getAsset();
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return path;
    }
    return "";
  }

  /**
   * Checks if this event represents a component-associated asset using Record Patterns.
   * 
   * @return true if the asset has an associated component
   */
  public boolean hasComponent() {
    Asset asset = getAsset();
    return asset != null && 
           asset.data() instanceof AssetData(var path, var kind, Optional.of(var component), var blob, var lastDownloaded, var blobStoreName, var blobSize);
  }

  /**
   * Gets the blob store name if available using Record Patterns.
   * 
   * @return the blob store name or empty string if not available
   */
  public String getBlobStoreName() {
    Asset asset = getAsset();
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var storeName, var blobSize)) {
      return storeName;
    }
    return "";
  }

  @Override
  public String toString() {
    return STR."AssetCreatedEvent{} \{super.toString()}";
  }
}