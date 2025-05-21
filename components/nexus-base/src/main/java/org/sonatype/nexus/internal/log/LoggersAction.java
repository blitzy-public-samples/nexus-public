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
package org.sonatype.nexus.internal.log;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.common.log.LogManager;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.Boolean.TRUE;

/**
 * Action to display configured loggers.
 *
 * @since 3.0
 */
@Named
@Command(name = "loggers", scope = "nexus", description = "Display loggers")
public class LoggersAction
    implements Action
{
  private final LogManager logManager;

  @Option(name = "-r", aliases = {"--reset"}, description = "Reset loggers")
  private Boolean reset;

  @Inject
  public LoggersAction(final LogManager logManager) {
    this.logManager = checkNotNull(logManager);
  }

  @Override
  public Object execute() throws Exception {
    return switch (reset) {
      case TRUE -> {
        logManager.resetLoggers();
        yield null;
      }
      case null, default -> {
        printLoggers();
        yield null;
      }
    };
  }
  
  /**
   * Prints the loggers in a formatted table using String Templates.
   */
  private void printLoggers() {
    // Find the maximum length of logger names for proper alignment
    int maxNameLength = logManager.getLoggers().keySet().stream()
        .mapToInt(String::length)
        .max()
        .orElse(10);
    
    // Ensure minimum column width
    maxNameLength = Math.max(maxNameLength, 4); // "Name" header length
    
    // Print header with proper alignment
    System.out.println(STR."\{String.format("%-" + maxNameLength + "s", "Name")} Level");
    System.out.println(STR."\{"-".repeat(maxNameLength)} -----");
    
    // Print each logger with proper alignment
    logManager.getLoggers()
        .keySet()
        .stream()
        .sorted()
        .forEach(key -> {
          String level = logManager.getLoggers().get(key);
          System.out.println(STR."\{String.format("%-" + maxNameLength + "s", key)} \{level}");
        });
  }
}