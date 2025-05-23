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
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.content.store.InternalIds.contentRepositoryId;

/**
 * Base {@link Asset} event.
 *
 * @since 3.26
 */
public class AssetEvent
    extends ContentStoreEvent
{
  private final Asset asset;

  /**
   * Creates a new event with the given asset.
   * 
   * @param asset the asset associated with this event (must not be null)
   * @throws NullPointerException if asset is null
   */
  protected AssetEvent(final Asset asset) {
    super(contentRepositoryId(asset));
    this.asset = checkNotNull(asset, "Asset cannot be null");
  }

  /**
   * Returns the asset associated with this event.
   * 
   * @return the asset (never null)
   */
  public Asset getAsset() {
    return asset;
  }

  /**
   * Extracts and formats asset information using Java 21 Record Patterns.
   * This method demonstrates how to safely extract and process asset data.
   * 
   * @return formatted asset information
   */
  protected String getAssetInfo() {
    if (asset instanceof Asset(var path, var kind, var component, var blob, var hasBlob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return STR."path=\{path}, kind=\{kind}, hasBlob=\{hasBlob}, size=\{blobSize}";
    }
    return asset.toString();
  }

  @Override
  public String toString() {
    return STR."AssetEvent{asset=\{getAssetInfo()}} \{super.toString()}";
  }
}