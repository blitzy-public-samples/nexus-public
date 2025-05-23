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

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AttributeChangeSet.AttributeChange;
import org.sonatype.nexus.repository.content.AttributeOperation;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event sent whenever an {@link Asset}'s attributes change.
 * <p>
 * This class is immutable and thread-safe, making it compatible with Java 21 Virtual Threads.
 * It leverages pattern matching when processing attribute changes.
 *
 * @since 3.26
 */
public class AssetAttributesEvent
    extends AssetUpdatedEvent
{
  private final List<AttributeChange> changes;

  /**
   * Creates a new event for the given asset and attribute changes.
   * <p>
   * The changes list is made immutable to ensure thread safety with Virtual Threads.
   *
   * @param asset the asset whose attributes changed (must not be null)
   * @param changes the list of attribute changes (must not be null)
   */
  public AssetAttributesEvent(
      final Asset asset,
      final List<AttributeChange> changes)
  {
    super(asset);
    this.changes = Collections.unmodifiableList(checkNotNull(changes));
  }

  /**
   * Gets the immutable list of attribute changes.
   * <p>
   * When processing these changes, you can leverage pattern matching with
   * the {@link AttributeOperation} enum for more concise code.
   *
   * @return immutable list of attribute changes
   */
  public List<AttributeChange> getChanges() {
    return changes;
  }
  
  /**
   * Processes the changes using pattern matching and returns a formatted summary.
   * <p>
   * This demonstrates how to use pattern matching with the AttributeChange class.
   *
   * @return a summary of the changes
   */
  public String processChanges() {
    return changes.stream()
        .map(change -> switch (change.getOperation()) {
          case SET -> STR."Set '\{change.getKey()}' to \{change.getValue()}";
          case REMOVE -> STR."Removed '\{change.getKey()}'";
          case APPEND -> STR."Appended \{change.getValue()} to '\{change.getKey()}'";
          case PREPEND -> STR."Prepended \{change.getValue()} to '\{change.getKey()}'";
          case OVERLAY -> STR."Overlaid \{change.getValue()} onto '\{change.getKey()}'";
        })
        .collect(Collectors.joining(", "));
  }

  @Override
  public String toString() {
    return STR."AssetAttributesEvent{changes=\{changes}} \{super.toString()}";
  }
}