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
   * Creates a new event with the given asset.
   * 
   * @param asset the asset that was created (must not be null)
   * @throws NullPointerException if asset is null
   */
  public AssetCreatedEvent(final Asset asset) {
    super(checkNotNull(asset, "Asset cannot be null"));
  }

  /**
   * Demonstrates using Record Patterns to safely extract asset information.
   * 
   * @param asset the asset to process
   * @return a description of the asset path and blob status
   */
  public static String describeAssetWithPatterns(final Object asset) {
    if (asset instanceof Asset(var path, var kind, var component, var blob, var hasBlob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return STR."Asset path: \{path}, kind: \{kind}, has blob: \{hasBlob}, blob store: \{blobStoreName}, size: \{blobSize}";
    }
    return "Not a valid Asset";
  }

  @Override
  public String toString() {
    return STR."AssetCreatedEvent{} \{super.toString()}";
  }
}