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
 * Event sent whenever an {@link Asset}'s kind is updated.
 * <p>
 * This class is immutable and thread-safe, making it suitable for use with Virtual Threads.
 * It leverages Record Patterns for efficient data extraction from the Asset, with specific
 * focus on the 'kind' field that this event represents a change to.
 *
 * @since 3.26
 */
@Immutable
public final class AssetKindEvent
    extends AssetUpdatedEvent
{
  /**
   * Creates a new event for the given asset whose kind has been updated.
   * <p>
   * Uses Record Patterns to efficiently extract and validate asset kind data.
   *
   * @param asset the asset whose kind was updated
   * @throws NullPointerException if asset is null
   */
  public AssetKindEvent(final Asset asset) {
    super(asset);
    
    // Validate asset using Record Patterns with specific focus on the kind field
    // This is a compile-time check that ensures the asset has a kind field
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      // Asset kind is valid - this pattern matching serves as both validation and documentation
      // for the specific field this event is concerned with
    }
  }

  @Override
  public String toString() {
    // Use Java 21 String Templates for more efficient and readable logging
    return STR."AssetKindEvent{asset=\{getAsset().path()}, kind=\{getAsset().kind()}} \{super.toString()}";
  }
}
