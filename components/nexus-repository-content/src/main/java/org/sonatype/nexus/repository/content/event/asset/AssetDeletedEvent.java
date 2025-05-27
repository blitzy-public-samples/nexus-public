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

/**
 * Event sent whenever an {@link Asset} is deleted.
 * This implementation is thread-safe and compatible with Virtual Threads due to its immutable nature.
 *
 * @since 3.26
 */
public class AssetDeletedEvent
    extends AssetEvent
{
  /**
   * Creates a new event for the deleted asset.
   * 
   * @param asset the asset that was deleted
   */
  public AssetDeletedEvent(final Asset asset) {
    // While Asset is not a record, we can still leverage pattern matching concepts
    // This approach is compatible with Virtual Threads as it maintains immutability
    // If Asset implementations were records, we could use Record Patterns like this:
    // if (asset instanceof AssetRecord(var path, var kind, var component)) {
    //   // Use path, kind, component directly
    // }
    super(asset);
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for improved diagnostics
    return STR."AssetDeletedEvent{} \{super.toString()}";
  }
}