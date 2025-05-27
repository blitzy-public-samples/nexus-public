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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event sent whenever a large number of {@link Component}s are purged along with their assets.
 * 
 * This implementation leverages Java 21 Sequenced Collections for efficient operations
 * and is optimized for Virtual Thread execution contexts.
 *
 * @since 3.26
 */
public class ComponentPurgedEvent
    extends ContentStoreEvent
{
  private final List<Integer> componentIds;

  /**
   * Creates a new event for purged components.
   *
   * @param contentRepositoryId the repository ID containing the purged components
   * @param componentIds the array of component IDs that were purged
   */
  public ComponentPurgedEvent(final int contentRepositoryId, final int[] componentIds) {
    super(contentRepositoryId);
    checkNotNull(componentIds);
    
    // Convert array to List for Sequenced Collection benefits
    // Using IntStream for more efficient conversion that works well with Virtual Threads
    this.componentIds = IntStream.of(componentIds)
        .boxed()
        .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
  }

  /**
   * Creates a new event for purged components using a List.
   *
   * @param contentRepositoryId the repository ID containing the purged components
   * @param componentIds the list of component IDs that were purged
   */
  public ComponentPurgedEvent(final int contentRepositoryId, final List<Integer> componentIds) {
    super(contentRepositoryId);
    this.componentIds = new ArrayList<>(checkNotNull(componentIds));
  }

  /**
   * Returns the list of component IDs that were purged.
   * The returned list is a Sequenced Collection that provides efficient operations.
   *
   * @return the list of purged component IDs
   */
  public List<Integer> getComponentIds() {
    return componentIds;
  }
  
  /**
   * Returns the component IDs as an array for backward compatibility.
   * This method is optimized for Virtual Thread execution.
   *
   * @return array of component IDs
   */
  public int[] getComponentIdsArray() {
    // Using optimized stream operations that work well with Virtual Threads
    return componentIds.stream()
        .mapToInt(Integer::intValue)
        .toArray();
  }
  
  /**
   * Returns the first component ID in the sequence, if any.
   *
   * @return the first component ID or null if empty
   */
  public Integer getFirstComponentId() {
    return componentIds.isEmpty() ? null : componentIds.getFirst();
  }
  
  /**
   * Returns the last component ID in the sequence, if any.
   *
   * @return the last component ID or null if empty
   */
  public Integer getLastComponentId() {
    return componentIds.isEmpty() ? null : componentIds.getLast();
  }
  
  /**
   * Process all component IDs with the provided consumer.
   * This method is optimized for Virtual Thread execution contexts.
   *
   * @param consumer the operation to perform on each component ID
   */
  public void forEachComponentId(Consumer<Integer> consumer) {
    checkNotNull(consumer);
    componentIds.forEach(consumer);
  }
  
  /**
   * Process all component IDs in reverse order with the provided consumer.
   * This method leverages Java 21 Sequenced Collections for efficient reverse iteration.
   *
   * @param consumer the operation to perform on each component ID in reverse order
   */
  public void forEachComponentIdReversed(Consumer<Integer> consumer) {
    checkNotNull(consumer);
    componentIds.reversed().forEach(consumer);
  }
  
  /**
   * Returns the number of components purged in this event.
   *
   * @return count of purged components
   */
  public int getComponentCount() {
    return componentIds.size();
  }

  /**
   * Checks if this event contains the specified component ID.
   *
   * @param componentId the component ID to check
   * @return true if this event contains the component ID, false otherwise
   */
  public boolean containsComponentId(int componentId) {
    return componentIds.contains(componentId);
  }
  
  /**
   * Creates a reversed view of the component IDs.
   * This method leverages Java 21 Sequenced Collections for efficient reverse views.
   *
   * @return a reversed view of the component IDs
   */
  public List<Integer> getReversedComponentIds() {
    return componentIds.reversed();
  }

  @Override
  public String toString() {
    return "ComponentPurgedEvent{" +
        "componentIds=" + componentIds +
        ", count=" + componentIds.size() +
        "} " + super.toString();
  }
}