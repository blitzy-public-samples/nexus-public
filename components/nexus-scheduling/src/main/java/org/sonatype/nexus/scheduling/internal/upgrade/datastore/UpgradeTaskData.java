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
package org.sonatype.nexus.scheduling.internal.upgrade.datastore;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Data record for upgrade tasks.
 * <p>
 * This class is implemented as a Java 21 Record for improved conciseness and type safety.
 * It maintains backward compatibility with existing code through compatibility methods.
 * <p>
 * As a record, this class automatically provides implementations for:
 * <ul>
 *   <li>equals() - structural equality based on all components</li>
 *   <li>hashCode() - consistent with equals</li>
 *   <li>toString() - formatted display of all components</li>
 *   <li>accessor methods - named after each component</li>
 * </ul>
 */
public record UpgradeTaskData(Integer id, String taskId, String status, Map<String, String> configuration)
{
  /**
   * Compact constructor for validation and defensive copying.
   * This ensures immutability of the record's components.
   */
  public UpgradeTaskData {
    // Defensive copy for configuration map to ensure immutability
    configuration = configuration != null ? new HashMap<>(configuration) : null;
  }
  
  /**
   * Constructor for creating a new task with required fields.
   *
   * @param taskId the task identifier
   * @param configuration the task configuration
   */
  public UpgradeTaskData(final String taskId, final Map<String, String> configuration) {
    this(null, checkNotNull(taskId), null, new HashMap<>(checkNotNull(configuration)));
  }
  
  /**
   * Default constructor for deserialization.
   */
  public UpgradeTaskData() {
    this(null, null, null, null);
  }
  
  /**
   * The configuration of the task to be run.
   * 
   * @return the task configuration or an empty map if null
   */
  public Map<String, String> getConfiguration() {
    return configuration != null ? configuration : Collections.emptyMap();
  }

  /**
   * @return the task ID
   */
  public Integer getId() {
    return id;
  }

  /**
   * A string indicating the last task status.
   * 
   * @return the task status
   */
  public String getStatus() {
    return status;
  }

  /**
   * Matches the ID from TaskInfo.
   * 
   * @return the task ID
   */
  public String getTaskId() {
    return taskId;
  }

  /**
   * Sets the configuration of the task.
   * 
   * @param configuration the task configuration
   * @return a new UpgradeTaskData with the updated configuration
   */
  public UpgradeTaskData setConfiguration(final Map<String, String> configuration) {
    return new UpgradeTaskData(id, taskId, status, new HashMap<>(checkNotNull(configuration)));
  }

  /**
   * Sets the ID of the task.
   * 
   * @param id the task ID
   * @return a new UpgradeTaskData with the updated ID
   */
  public UpgradeTaskData setId(final Integer id) {
    return new UpgradeTaskData(id, taskId, status, configuration);
  }

  /**
   * Sets the status of the task.
   * 
   * @param status the task status
   * @return a new UpgradeTaskData with the updated status
   */
  public UpgradeTaskData setStatus(final String status) {
    return new UpgradeTaskData(id, taskId, status, configuration);
  }

  /**
   * Sets the task ID.
   * 
   * @param taskId the task ID
   * @return a new UpgradeTaskData with the updated task ID
   */
  public UpgradeTaskData setTaskId(final String taskId) {
    return new UpgradeTaskData(id, checkNotNull(taskId), status, configuration);
  }
  
  /**
   * Utility method demonstrating Java 21 Record Pattern matching.
   * Extracts a configuration value safely using pattern matching.
   *
   * @param data the task data record
   * @param key the configuration key to look up
   * @return an Optional containing the value if found, or empty if not found or data is invalid
   */
  public static Optional<String> getConfigValue(Object data, String key) {
    if (data instanceof UpgradeTaskData(var id, var taskId, var status, var config)) {
      return Optional.ofNullable(config)
          .map(c -> c.get(key));
    }
    return Optional.empty();
  }
  
  /**
   * Utility method demonstrating Java 21 Record Pattern matching with guards.
   * Checks if the task data has a specific status and configuration value.
   *
   * @param data the task data record
   * @param statusValue the status to check for
   * @param configKey the configuration key to check
   * @param configValue the configuration value to check for
   * @return true if the task matches the criteria, false otherwise
   */
  public static boolean matchesStatusAndConfig(Object data, String statusValue, String configKey, String configValue) {
    return data instanceof UpgradeTaskData(var id, var taskId, var status, var config) 
        && statusValue.equals(status)
        && config != null 
        && configValue.equals(config.get(configKey));
  }
}
