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
package org.sonatype.nexus.internal.log.overrides.datastore;

import org.sonatype.nexus.common.event.EventWithSource;

/**
 * An event fired when the logger overrides has changed in order to propagate changes to all nodes.
 * <p>
 * Optimized for Java 21 with Pattern Matching support for improved event handling.
 * <p>
 * This class demonstrates several Java 21 features:
 * <ul>
 *   <li>Pattern Matching for switch with guarded patterns in {@link #processEvent()}</li>
 *   <li>Pattern Matching for instanceof with record patterns in {@link #matchesDescriptor(Object)}</li>
 *   <li>Record patterns for structured data in {@link EventDescriptor}</li>
 * </ul>
 * <p>
 * These features improve code readability and reduce boilerplate when handling different action types.
 */
public class LoggerOverridesEvent
    extends EventWithSource
{
  private String name;

  private String level;

  private Action action;

  /**
   * Default constructor for deserialization.
   */
  public LoggerOverridesEvent() {
    // deserialization
  }

  /**
   * Creates a new logger overrides event with the specified parameters.
   *
   * @param name   the logger name
   * @param level  the logger level
   * @param action the action to perform
   */
  public LoggerOverridesEvent(final String name, final String level, final Action action) {
    this.name = name;
    this.level = level;
    this.action = action;
  }

  /**
   * @return the logger name
   */
  public String getName() {
    return name;
  }

  /**
   * @param name the logger name to set
   */
  public void setName(final String name) {
    this.name = name;
  }

  /**
   * @return the logger level
   */
  public String getLevel() {
    return level;
  }

  /**
   * @param level the logger level to set
   */
  public void setLevel(final String level) {
    this.level = level;
  }

  /**
   * @return the action to perform
   */
  public Action getAction() {
    return action;
  }

  /**
   * @param action the action to set
   */
  public void setAction(final Action action) {
    this.action = action;
  }

  /**
   * Processes this event using Java 21 Pattern Matching for switch.
   * This method demonstrates how to handle different action types using the new switch pattern matching.
   *
   * @return a description of the action being performed
   */
  public String processEvent() {
    // Using Java 21 Pattern Matching for switch with guarded patterns
    return switch (action) {
      case CHANGE when name != null && level != null -> 
          "Changing logger '" + name + "' to level '" + level + "'";
      case RESET when name != null -> 
          "Resetting logger '" + name + "' to default level";
      case RESET_ALL -> 
          "Resetting all loggers to default levels";
      default -> 
          "Unknown action";
    };
  }
  
  /**
   * Demonstrates Java 21 Pattern Matching for instanceof with the EventDescriptor record.
   * This method shows how to use pattern matching with instanceof to simplify type checking and casting.
   *
   * @param obj the object to check
   * @return true if the object is an EventDescriptor with the same action as this event
   */
  public boolean matchesDescriptor(Object obj) {
    // Using Java 21 Pattern Matching for instanceof
    return obj instanceof EventDescriptor(String descriptorName, String descriptorLevel, Action descriptorAction)
        && descriptorAction == this.action
        && (descriptorName == null ? this.name == null : descriptorName.equals(this.name))
        && (descriptorLevel == null ? this.level == null : descriptorLevel.equals(this.level));
  }

  /**
   * Creates an event descriptor using Java 21 record patterns.
   * This method demonstrates how to use record patterns with this event type.
   *
   * @return a record containing the event details
   */
  public EventDescriptor toDescriptor() {
    return new EventDescriptor(name, level, action);
  }

  /**
   * Record for pattern matching with LoggerOverridesEvent data.
   * This record facilitates Java 21 pattern matching when processing events.
   */
  public record EventDescriptor(String name, String level, Action action) {
    /**
     * Determines if this descriptor represents a change action.
     *
     * @return true if this is a change action with valid name and level
     */
    public boolean isChangeAction() {
      return action == Action.CHANGE && name != null && level != null;
    }

    /**
     * Determines if this descriptor represents a reset action.
     *
     * @return true if this is a reset action with a valid name
     */
    public boolean isResetAction() {
      return action == Action.RESET && name != null;
    }

    /**
     * Determines if this descriptor represents a reset-all action.
     *
     * @return true if this is a reset-all action
     */
    public boolean isResetAllAction() {
      return action == Action.RESET_ALL;
    }
  }

  /**
   * Enum representing the possible actions for logger overrides.
   */
  public enum Action
  {
    CHANGE, RESET, RESET_ALL
  }
}