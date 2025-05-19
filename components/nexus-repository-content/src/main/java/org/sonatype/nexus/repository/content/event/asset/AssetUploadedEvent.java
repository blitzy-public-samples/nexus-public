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

import java.util.Objects;
import java.util.Optional;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetData;

/**
 * Event sent whenever an {@link Asset} is uploaded.
 *
 * @since 3.26
 */
public class AssetUploadedEvent
    extends AssetUpdatedEvent
{
  /**
   * Creates a new asset uploaded event.
   *
   * @param asset the uploaded asset (must not be null)
   * @throws NullPointerException if asset is null
   */
  public AssetUploadedEvent(final Asset asset) {
    super(Objects.requireNonNull(asset, "Asset cannot be null"));
  }

  /**
   * Extracts asset information using pattern matching.
   * 
   * @return Optional containing the asset path if available
   * @since 3.41
   */
  public Optional<String> extractAssetPath() {
    Asset asset = getAsset();
    if (asset instanceof Asset a && a.data() instanceof AssetData(var path, var kind, var component, var blob, var lastDownloaded, var blobStoreName, var blobSize)) {
      return Optional.of(path);
    }
    return Optional.empty();
  }

  @Override
  public String toString() {
    return STR."AssetUploadedEvent{} \{super.toString()}";
  }
}