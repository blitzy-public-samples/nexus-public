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

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.common.event.EventWithSource;

/**
 * An event fired when the logger overrides has changed in order to propagate changes to all nodes.
 */
public class LoggerOverridesEvent
    extends EventWithSource
{
  private String name;

  private String level;

  private Action action;

  public LoggerOverridesEvent() {
    // deserialization
  }

  public LoggerOverridesEvent(final String name, final String level, final Action action) {
    this.name = name;
    this.level = level;
    this.action = action;
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getLevel() {
    return level;
  }

  public void setLevel(final String level) {
    this.level = level;
  }

  public Action getAction() {
    return action;
  }

  public void setAction(final Action action) {
    this.action = action;
  }

  /**
   * Process this event based on its action type using Pattern Matching for switch.
   * 
   * @return A description of the action taken
   */
  public String processEvent() {
    return switch (action) {
      case CHANGE -> handleChange();
      case RESET -> handleReset();
      case RESET_ALL -> handleResetAll();
    };
  }

  /**
   * Process this event based on its action type and additional conditions using Pattern Matching with guarded patterns.
   * 
   * @param defaultLevel The default level to use if none is specified
   * @return A description of the action taken
   */
  public String processEventWithGuards(String defaultLevel) {
    return switch (action) {
      case CHANGE when level != null -> STR."Changed logger \{name} to level \{level}";
      case CHANGE when level == null -> {
        this.level = defaultLevel;
        yield STR."Changed logger \{name} to default level \{defaultLevel}";
      }
      case RESET -> STR."Reset logger \{name} to default configuration";
      case RESET_ALL -> "Reset all loggers to default configuration";
    };
  }

  /**
   * Demonstrates pattern matching on the event object itself.
   * 
   * @param event The event to process
   * @return A description of the action taken
   */
  public static String processEventObject(Object event) {
    return switch (event) {
      case LoggerOverridesEvent e when e.getAction() == Action.CHANGE -> 
          STR."Change event for logger \{e.getName()} to level \{e.getLevel()}";
      case LoggerOverridesEvent e when e.getAction() == Action.RESET -> 
          STR."Reset event for logger \{e.getName()}";
      case LoggerOverridesEvent e when e.getAction() == Action.RESET_ALL -> 
          "Reset all loggers event";
      case null -> "Null event received";
      default -> "Unknown event type";
    };
  }

  private String handleChange() {
    return STR."Changing logger \{name} to level \{level}";
  }

  private String handleReset() {
    return STR."Resetting logger \{name}";
  }

  private String handleResetAll() {
    return "Resetting all loggers";
  }

  @Override
  public String toString() {
    return switch (action) {
      case CHANGE -> STR."LoggerOverridesEvent[action=CHANGE, name=\{name}, level=\{level}]";
      case RESET -> STR."LoggerOverridesEvent[action=RESET, name=\{name}]";
      case RESET_ALL -> "LoggerOverridesEvent[action=RESET_ALL]";
    };
  }

  public enum Action
  {
    CHANGE, RESET, RESET_ALL
  }
}