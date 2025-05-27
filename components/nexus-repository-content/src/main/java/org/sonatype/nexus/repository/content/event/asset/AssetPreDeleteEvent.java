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
   * @param asset the asset being deleted, must not be null
   * @throws NullPointerException if asset is null
   */
  public AssetPreDeleteEvent(final Asset asset) {
    super(checkNotNull(asset, "Asset cannot be null"));
  }

  /**
   * Safely processes the asset using pattern matching for improved type safety.
   *
   * @param processor the function to process the asset
   * @param <R> the return type of the processor
   * @return the result of processing the asset
   */
  public <R> R processAsset(java.util.function.Function<Asset, R> processor) {
    if (getAsset() instanceof Asset asset) {
      return processor.apply(asset);
    }
    throw new IllegalStateException("Asset is not of expected type");
  }

  @Override
  public String toString() {
    return STR."AssetPreDeleteEvent{} \{super.toString()}";
  }
}
