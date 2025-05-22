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
package org.sonatype.nexus.distributed.event.service.api;

/**
 * The event type associated with a {@link DistributedEvent}
 *
 * @since 3.38
 */
public enum EventType
{
  CREATED, UPDATED, DELETED, CANCELLED;
  
  /**
   * Converts an EventType to a human-readable description using Pattern Matching for switch.
   * <p>
   * This method demonstrates the use of Java 21's Pattern Matching for switch to create
   * more readable and concise code when handling different enum values.
   *
   * @param eventType the event type to convert
   * @return a human-readable description of the event type
   */
  public static String toDescription(EventType eventType) {
    return switch (eventType) {
      case CREATED -> "Creation event";
      case UPDATED -> "Update event";
      case DELETED -> "Deletion event";
      case CANCELLED -> "Cancellation event";
      // No default case needed as all enum values are covered
    };
  }
  
  /**
   * Determines if the given event type is a lifecycle event using Pattern Matching for switch.
   * <p>
   * Lifecycle events are considered to be CREATED and DELETED events. This method demonstrates
   * how Pattern Matching for switch can group multiple cases together for more expressive code.
   *
   * @param eventType the event type to check
   * @return true if the event type is a lifecycle event, false otherwise
   */
  public static boolean isLifecycleEvent(EventType eventType) {
    return switch (eventType) {
      case CREATED, DELETED -> true;
      case UPDATED, CANCELLED -> false;
      // No default case needed as all enum values are covered
    };
  }
  
  /**
   * Classifies an event type into a category using Pattern Matching for switch with guarded patterns.
   * <p>
   * This method demonstrates how Pattern Matching for switch can be used with more complex
   * conditions to categorize enum values.
   *
   * @param eventType the event type to classify
   * @return the category of the event type
   */
  public static String classifyEvent(EventType eventType) {
    return switch (eventType) {
      case CREATED when isLifecycleEvent(eventType) -> "Primary lifecycle event";
      case DELETED when isLifecycleEvent(eventType) -> "Primary lifecycle event";
      case UPDATED -> "Modification event";
      case CANCELLED -> "Administrative event";
      // No default case needed as all enum values are covered
    };
  }
}
