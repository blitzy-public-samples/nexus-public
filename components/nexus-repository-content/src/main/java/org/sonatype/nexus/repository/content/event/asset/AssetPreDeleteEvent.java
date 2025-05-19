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
import org.sonatype.nexus.repository.content.Component;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event sent just before an {@link Asset} is deleted.
 *
 * @since 3.27
 */
public class AssetPreDeleteEvent
    extends AssetEvent
{
  /**
   * Creates a new event for the given asset.
   * 
   * @param asset the asset being deleted (must not be null)
   * @throws NullPointerException if asset is null
   */
  public AssetPreDeleteEvent(final Asset asset) {
    super(checkNotNull(asset, "Asset cannot be null"));
  }
  
  /**
   * Extracts asset path and component information using Java 21 Record Patterns.
   * 
   * @return asset path and component information if available
   */
  public String getAssetInfo() {
    Asset asset = getAsset();
    
    // Using Java 21 Record Patterns for type-safe extraction of asset data
    if (asset != null && asset.data() instanceof AssetData(var path, var kind, Optional<Component> component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return component.map(c -> STR."Asset at \{path} belonging to component \{c.name()}")
          .orElse(STR."Asset at \{path} with no component");
    }
    
    return "Asset information unavailable";
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for more efficient string formatting
    return STR."AssetPreDeleteEvent{} \{super.toString()}";
  }
}