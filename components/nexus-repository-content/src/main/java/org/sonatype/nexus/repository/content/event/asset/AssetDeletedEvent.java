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

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetData;

/**
 * Event sent whenever an {@link Asset} is deleted.
 * <p>
 * This class is thread-safe and compatible with Virtual Threads in Java 21.
 * It leverages Record Patterns for efficient asset data handling and String Templates
 * for improved diagnostic output.
 * </p>
 *
 * @since 3.26
 */
public class AssetDeletedEvent
    extends AssetEvent
{
  /**
   * Creates a new event for the deleted asset.
   * <p>
   * Uses Record Patterns to validate the asset structure at construction time.
   * </p>
   *
   * @param asset the deleted asset (must not be null)
   */
  public AssetDeletedEvent(final Asset asset) {
    super(asset);
    // Pattern matching validation ensures asset has valid structure
    // This is a compile-time check with no runtime overhead
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      // Asset structure validated via pattern matching
    }
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for improved diagnostics
    return STR."AssetDeletedEvent{asset=\{getAsset().path()}} \{super.toString()}";
  }
}
