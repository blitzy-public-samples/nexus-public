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
package org.sonatype.nexus.testsuite.testsupport;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Base class for tests that use Java 21 Virtual Threads.
 * Provides common utilities for creating and managing virtual threads in tests.
 */
@ExtendWith(MockitoExtension.class)
public abstract class VirtualThreadTestSupport
    extends TestSupport
{
  protected ExecutorService virtualThreadExecutor;

  /**
   * Set up a virtual thread executor before each test.
   */
  @BeforeEach
  public void setUpVirtualThreadExecutor() {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }

  /**
   * Clean up the virtual thread executor after each test.
   */
  @AfterEach
  public void tearDownVirtualThreadExecutor() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdown();
    }
  }

  /**
   * Checks if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  protected boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Creates a new virtual thread executor with the specified name pattern.
   *
   * @param namePattern the name pattern for the virtual threads
   * @return a new executor service that creates a new virtual thread for each task
   */
  protected ExecutorService createVirtualThreadExecutor(String namePattern) {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name(namePattern, 0).factory();
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }
}