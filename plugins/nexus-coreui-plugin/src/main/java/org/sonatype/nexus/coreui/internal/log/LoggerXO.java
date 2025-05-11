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
package org.sonatype.nexus.coreui.internal.log;

import java.util.Map;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;

import org.sonatype.nexus.common.log.LoggerLevel;

/**
 * Logger exchange object.
 * Refactored as a Java 21 record for immutability and simplified data handling.
 */
public record LoggerXO(
    @NotEmpty String name,
    @NotNull LoggerLevel level,
    boolean override
) {
  /**
   * Creates a LoggerXO instance from a Map.Entry containing logger name and level.
   * Uses pattern matching to extract key-value pairs from the entry.
   *
   * @param entry Map.Entry containing logger name and level
   * @return new LoggerXO instance with override set to true
   */
  public static LoggerXO fromEntry(Map.Entry<String, LoggerLevel> entry) {
    // Using record pattern matching to destructure the Map.Entry
    if (entry instanceof Map.Entry<String, LoggerLevel>(var name, var level)) {
      return new LoggerXO(name, level, true);
    }
    throw new IllegalArgumentException("Invalid Map.Entry provided");
  }
}