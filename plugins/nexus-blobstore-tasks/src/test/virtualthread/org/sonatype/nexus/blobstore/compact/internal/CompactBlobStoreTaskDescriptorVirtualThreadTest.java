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
package org.sonatype.nexus.blobstore.compact.internal;

import java.util.concurrent.ExecutionException;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sonatype.nexus.scheduling.TaskDescriptorSupport.MULTINODE_KEY;

/**
 * Tests for {@link CompactBlobStoreTaskDescriptor} with Java 21 Virtual Threads.
 *
 * @since 3.60
 */
public class CompactBlobStoreTaskDescriptorVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private CompactBlobStoreTaskDescriptor underTest;

  private TaskConfiguration taskConfiguration;

  @BeforeEach
  public void setUp() {
    // Skip test if Virtual Threads are not supported
    assumeVirtualThreadSupported();
    
    underTest = new CompactBlobStoreTaskDescriptor();
    taskConfiguration = new TaskConfiguration();
  }

  @Test
  public void initializeConfigurationWithVirtualThread() throws Exception {
    // Execute the task descriptor initialization in a Virtual Thread
    callVirtual(() -> {
      underTest.initializeConfiguration(taskConfiguration);
      assertFalse(taskConfiguration.getBoolean(MULTINODE_KEY, true), 
          "Task should not be configured for multi-node execution");
      return null;
    });
  }

  @Test
  public void verifyNoThreadPinning() throws Exception {
    // Test that the task descriptor doesn't cause thread pinning
    boolean pinningDetected = detectThreadPinning(() -> {
      underTest.initializeConfiguration(taskConfiguration);
    });
    
    assertFalse(pinningDetected, "Task descriptor should not cause thread pinning");
  }
  
  @Test
  public void verifyTaskDescriptorCompatibleWithVirtualThreads() throws Exception {
    // Verify that the task descriptor works correctly with Virtual Threads
    // by executing multiple concurrent initializations
    int concurrentTasks = 10;
    runConcurrently(concurrentTasks, () -> {
      TaskConfiguration config = new TaskConfiguration();
      underTest.initializeConfiguration(config);
      assertFalse(config.getBoolean(MULTINODE_KEY, true), 
          "Task should not be configured for multi-node execution");
    });
    
    // If we reach here without exceptions, the test passes
    assertTrue(true, "Task descriptor should be compatible with Virtual Threads");
  }
}