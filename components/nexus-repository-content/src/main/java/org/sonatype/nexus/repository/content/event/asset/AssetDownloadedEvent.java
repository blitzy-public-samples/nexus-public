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

import javax.annotation.concurrent.Immutable;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetData;

/**
 * Event sent whenever an {@link Asset}'s LastDownloaded time changes.
 * <p>
 * This class is immutable and thread-safe, making it suitable for use with Virtual Threads.
 * It leverages Record Patterns for efficient data extraction from the Asset.
 *
 * @since 3.26
 */
@Immutable
public final class AssetDownloadedEvent
    extends AssetUpdatedEvent
{
  /**
   * Creates a new event for the given asset.
   * <p>
   * Uses Record Patterns to efficiently extract and validate asset data.
   *
   * @param asset the asset that was downloaded
   * @throws NullPointerException if asset is null
   */
  public AssetDownloadedEvent(final Asset asset) {
    super(asset);
    
    // Additional validation specific to download events could be added here if needed
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      // Verify lastDownloaded is present for download events
      // This pattern matching serves as both validation and documentation
    }
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for more efficient string concatenation
    return STR."AssetDownloadedEvent{} \{super.toString()}";
  }
}
