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
package org.sonatype.nexus.logging.task;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;
import org.sonatype.nexus.common.thread.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link TaskLoggerHelper}.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.experimental.categories.Category(VirtualThreadTestGroup.class)
public class TaskLoggerHelperTest
    extends TestSupport
{
  @Mock
  private Logger logger;

  @Mock
  private TaskLogger taskLogger;

  @Test
  void helperBasicFunctionality() {
    assertNull(TaskLoggerHelper.get());

    TaskLoggerHelper.start(taskLogger);
    assertNotNull(TaskLoggerHelper.get());

    TaskLoggingEvent event = new TaskLoggingEvent(logger, "message");
    TaskLoggerHelper.progress(event);
    verify(taskLogger).progress(event);

    TaskLoggerHelper.finish();
    assertNull(TaskLoggerHelper.get());
  }

  @Test
  void helperWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start the task logger
    TaskLoggerHelper.start(taskLogger);
    assertNotNull(TaskLoggerHelper.get());
    
    // Run a task in a virtual thread that uses the task logger
    executor.submit(() -> {
      try {
        // Verify the helper is accessible from the virtual thread
        assertNotNull(TaskLoggerHelper.get());
        
        // Log a message from the virtual thread
        TaskLoggingEvent event = new TaskLoggingEvent(logger, "virtual thread message");
        TaskLoggerHelper.progress(event);
        
        // Verify the message was logged
        verify(taskLogger).progress(event);
      } finally {
        latch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Clean up
    TaskLoggerHelper.finish();
    assertNull(TaskLoggerHelper.get());
    executor.shutdown();
  }
}