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

import java.util.Arrays;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event sent just before a large number of {@link Asset}s without components are purged.
 *
 * @since 3.27
 */
public class AssetPrePurgeEvent
    extends ContentStoreEvent
{
  private final int[] assetIds;

  /**
   * Creates a new event for assets about to be purged.
   *
   * @param contentRepositoryId the repository ID
   * @param assetIds the IDs of assets to be purged
   * @throws NullPointerException if assetIds is null
   */
  public AssetPrePurgeEvent(final int contentRepositoryId, final int[] assetIds) {
    super(contentRepositoryId);
    this.assetIds = checkNotNull(assetIds).clone(); // Defensive copy for thread safety
  }

  /**
   * Gets the IDs of assets to be purged.
   *
   * @return a defensive copy of the asset IDs array
   */
  public int[] getAssetIds() {
    return assetIds.clone(); // Return defensive copy for thread safety
  }

  @Override
  public String toString() {
    return STR."""
        AssetPrePurgeEvent{
        assetIds=\{Arrays.toString(assetIds)}
        } \{super.toString()}"""; // Using Java 21 String Templates for improved diagnostics
  }
}