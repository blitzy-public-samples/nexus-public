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

import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AttributeChangeSet.AttributeChange;
import org.sonatype.nexus.repository.content.AttributeOperation;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Event sent whenever an {@link Asset}'s attributes change.
 *
 * @since 3.26
 */
public class AssetAttributesEvent
    extends AssetUpdatedEvent
{
  private final List<AttributeChange> changes;

  public AssetAttributesEvent(
      final Asset asset,
      final List<AttributeChange> changes)
  {
    super(asset);
    this.changes = Collections.unmodifiableList(checkNotNull(changes));
  }

  /**
   * Returns an unmodifiable view of the attribute changes.
   * 
   * @return immutable list of attribute changes for thread safety with Virtual Threads
   */
  public List<AttributeChange> getChanges() {
    return changes;
  }
  
  /**
   * Process attribute changes using pattern matching to extract operation, key, and value.
   * 
   * @param processor the function to process each attribute change
   */
  public void processChanges(AttributeChangeProcessor processor) {
    for (AttributeChange change : changes) {
      // Using record pattern matching to destructure the AttributeChange
      // Note: AttributeChange is not a record, so we use traditional pattern matching
      if (change != null) {
        AttributeOperation operation = change.getOperation();
        String key = change.getKey();
        Object value = change.getValue();
        processor.process(operation, key, value);
      }
    }
  }

  @Override
  public String toString() {
    return STR"AssetAttributesEvent{changes=\{changes}} \{super.toString()}";
  }
  
  /**
   * Functional interface for processing attribute changes.
   */
  @FunctionalInterface
  public interface AttributeChangeProcessor {
    void process(AttributeOperation operation, String key, Object value);
  }
}
