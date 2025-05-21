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

import java.util.Objects;

import org.sonatype.nexus.common.entity.ContinuationAware;

/**
 * Data object for the {@code logging_overrides} table.
 */
public class LoggingOverridesData
    implements ContinuationAware
{
  Integer id; // NOSONAR: internal id

  private String name;

  private String level;

  /**
   * Record representing logging override data for efficient pattern matching.
   */
  public record LoggingOverride(String name, String level) {
    /**
     * Creates a new LoggingOverride with validated parameters.
     *
     * @param name the logger name
     * @param level the logging level
     * @throws NullPointerException if name or level is null
     */
    public LoggingOverride {
      Objects.requireNonNull(name, "Logger name cannot be null");
      Objects.requireNonNull(level, "Logging level cannot be null");
    }
  }

  public LoggingOverridesData() {
  }

  public LoggingOverridesData(final String name, final String level) {
    // Use the record's validation logic to ensure non-null values
    var override = new LoggingOverride(name, level);
    this.name = override.name();
    this.level = override.level();
  }

  /**
   * Creates a LoggingOverridesData instance from a LoggingOverride record.
   *
   * @param override the logging override record
   * @return a new LoggingOverridesData instance
   */
  public static LoggingOverridesData from(final LoggingOverride override) {
    return new LoggingOverridesData(override.name(), override.level());
  }

  /**
   * Converts this data object to a LoggingOverride record.
   *
   * @return a LoggingOverride record representing this data
   */
  public LoggingOverride toRecord() {
    return new LoggingOverride(name, level);
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = Objects.requireNonNull(name, "Logger name cannot be null");
  }

  public String getLevel() {
    return level;
  }

  public void setLevel(final String level) {
    this.level = Objects.requireNonNull(level, "Logging level cannot be null");
  }

  @Override
  public String nextContinuationToken() {
    return id != null ? Integer.toString(id) : null;
  }
}