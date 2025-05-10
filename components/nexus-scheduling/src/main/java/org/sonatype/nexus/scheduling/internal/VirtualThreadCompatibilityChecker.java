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
package org.sonatype.nexus.scheduling.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;

/**
 * Provides utilities for checking if tasks are compatible with virtual threads.
 * 
 * This component maintains a list of task types that are known to be incompatible with
 * virtual threads, typically because they use synchronized blocks extensively, rely on
 * thread-local variables, or use native methods that would cause thread pinning.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadCompatibilityChecker
    extends ComponentSupport
{
  private final List<String> incompatibleTaskTypes = Collections.synchronizedList(new ArrayList<>());
  private final List<String> incompatibleTaskOperations = Collections.synchronizedList(new ArrayList<>());
  
  public VirtualThreadCompatibilityChecker() {
    // Initialize with known incompatible task types
    // These are task types that are known to cause thread pinning or other issues with virtual threads
    incompatibleTaskTypes.add("script"); // Script tasks may use libraries with synchronized blocks
    incompatibleTaskTypes.add("legacy-migration"); // Legacy migration tasks may use old APIs with synchronization
    
    // Initialize with known incompatible operations
    // These are operations or configurations that indicate a task might not be compatible with virtual threads
    incompatibleTaskOperations.add("native-execution"); // Tasks that execute native code
    incompatibleTaskOperations.add("thread-local-storage"); // Tasks that rely heavily on ThreadLocal
    incompatibleTaskOperations.add("synchronized-blocks"); // Tasks known to use many synchronized blocks
  }
  
  /**
   * Checks if a task is compatible with virtual threads based on its configuration.
   * 
   * @param config the task configuration to check
   * @return true if the task is compatible with virtual threads, false otherwise
   */
  public boolean isCompatibleWithVirtualThreads(TaskConfiguration config) {
    // Check if the task type is in the incompatible list
    if (incompatibleTaskTypes.contains(config.getTypeId())) {
      log.debug("Task type {} is known to be incompatible with virtual threads", config.getTypeId());
      return false;
    }
    
    // Check if any incompatible operations are specified in the task configuration
    Map<String, String> taskConfig = config.asMap();
    for (String operation : incompatibleTaskOperations) {
      if (taskConfig.containsKey(operation) && Boolean.parseBoolean(taskConfig.get(operation))) {
        log.debug("Task {} uses operation {} which is incompatible with virtual threads", 
            config.getName(), operation);
        return false;
      }
    }
    
    // Check for explicit virtual thread compatibility flag
    if (taskConfig.containsKey("virtual-thread-compatible")) {
      boolean compatible = Boolean.parseBoolean(taskConfig.get("virtual-thread-compatible"));
      if (!compatible) {
        log.debug("Task {} is explicitly marked as incompatible with virtual threads", config.getName());
        return false;
      }
    }
    
    // By default, assume the task is compatible with virtual threads
    return true;
  }
  
  /**
   * Registers a task type as incompatible with virtual threads.
   * 
   * @param taskType the task type to register as incompatible
   */
  public void registerIncompatibleTaskType(String taskType) {
    if (!incompatibleTaskTypes.contains(taskType)) {
      incompatibleTaskTypes.add(taskType);
      log.info("Registered task type {} as incompatible with virtual threads", taskType);
    }
  }
  
  /**
   * Registers an operation as incompatible with virtual threads.
   * 
   * @param operation the operation to register as incompatible
   */
  public void registerIncompatibleOperation(String operation) {
    if (!incompatibleTaskOperations.contains(operation)) {
      incompatibleTaskOperations.add(operation);
      log.info("Registered operation {} as incompatible with virtual threads", operation);
    }
  }
  
  /**
   * @return a list of task types that are known to be incompatible with virtual threads
   */
  public List<String> getIncompatibleTaskTypes() {
    return new ArrayList<>(incompatibleTaskTypes);
  }
  
  /**
   * @return a list of operations that are known to be incompatible with virtual threads
   */
  public List<String> getIncompatibleTaskOperations() {
    return new ArrayList<>(incompatibleTaskOperations);
  }
}