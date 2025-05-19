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
package org.sonatype.nexus.repository.content.event.component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.SequencedCollection;

import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.unmodifiableSequencedCollection;

/**
 * Event sent whenever a large number of {@link Component}s are purged along with their assets.
 *
 * @since 3.26
 */
public class ComponentPurgedEvent
    extends ContentStoreEvent
{
  private final SequencedCollection<Integer> componentIds;

  /**
   * Creates a new event with the given component IDs.
   *
   * @param contentRepositoryId the repository ID
   * @param componentIds the IDs of the purged components
   */
  public ComponentPurgedEvent(final int contentRepositoryId, final int[] componentIds) {
    super(contentRepositoryId);
    checkNotNull(componentIds);
    
    // Convert array to SequencedCollection
    ArrayList<Integer> idList = new ArrayList<>(componentIds.length);
    for (int id : componentIds) {
      idList.add(id);
    }
    this.componentIds = unmodifiableSequencedCollection(idList);
  }

  /**
   * Creates a new event with the given component IDs.
   *
   * @param contentRepositoryId the repository ID
   * @param componentIds the collection of purged component IDs
   */
  public ComponentPurgedEvent(final int contentRepositoryId, final Collection<Integer> componentIds) {
    super(contentRepositoryId);
    checkNotNull(componentIds);
    
    // Convert collection to SequencedCollection if needed
    if (componentIds instanceof SequencedCollection) {
      this.componentIds = unmodifiableSequencedCollection((SequencedCollection<Integer>) componentIds);
    } else {
      this.componentIds = unmodifiableSequencedCollection(new ArrayList<>(componentIds));
    }
  }

  /**
   * Returns the IDs of the purged components as a SequencedCollection.
   * 
   * @return sequenced collection of component IDs
   */
  public SequencedCollection<Integer> getComponentIds() {
    return componentIds;
  }
  
  /**
   * Returns the IDs of the purged components as an array for backward compatibility.
   * 
   * @return array of component IDs
   * @deprecated Use {@link #getComponentIds()} instead which returns a SequencedCollection
   */
  @Deprecated
  public int[] getComponentIdsArray() {
    int[] result = new int[componentIds.size()];
    int i = 0;
    for (Integer id : componentIds) {
      result[i++] = id;
    }
    return result;
  }

  /**
   * Checks if the event contains the specified component ID.
   *
   * @param componentId the component ID to check
   * @return true if the event contains the component ID, false otherwise
   */
  public boolean containsComponentId(int componentId) {
    return componentIds.contains(componentId);
  }

  @Override
  public String toString() {
    return "ComponentPurgedEvent{" +
        "componentIds=" + componentIds +
        "} " + super.toString();
  }
}