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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;
import org.sonatype.nexus.common.thread.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Tag;
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
@Tag("VirtualThreadTestGroup")
public class TaskLoggerHelperTest
    extends TestSupport
{
  @Mock
  private Logger logger;

  @Mock
  private TaskLogger taskLogger;

  @Test
  void shouldInitializeAndCleanupTaskLogger() {
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
  void shouldWorkWithVirtualThreads() throws Exception {
    // Create a virtual thread executor if running on Java 21+
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task to run on a virtual thread
      Future<?> future = executor.submit(() -> {
        // Initialize the TaskLogger on a virtual thread
        assertNull(TaskLoggerHelper.get());
        
        TaskLoggerHelper.start(taskLogger);
        assertNotNull(TaskLoggerHelper.get());
        
        // Log a message from the virtual thread
        TaskLoggingEvent event = new TaskLoggingEvent(logger, "message from virtual thread");
        TaskLoggerHelper.progress(event);
        verify(taskLogger).progress(event);
        
        // Clean up
        TaskLoggerHelper.finish();
        assertNull(TaskLoggerHelper.get());
      });
      
      // Wait for the virtual thread task to complete
      future.get();
    }
  }
}