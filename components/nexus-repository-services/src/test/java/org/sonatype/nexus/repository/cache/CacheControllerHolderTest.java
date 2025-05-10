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
package org.sonatype.nexus.repository.cache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.repository.cache.CacheControllerHolder.CacheType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link CacheControllerHolder}.
 */
public class CacheControllerHolderTest
{
  private static final CacheType TEST = new CacheType("TEST");

  private CacheController contentCacheController = new CacheController(1000, "content");

  private CacheController metadataCacheController = new CacheController(1000, "metadata");

  private CacheControllerHolder underTest;

  @BeforeEach
  public void setUp() {
    this.underTest = new CacheControllerHolder(contentCacheController, metadataCacheController);
  }

  @Test
  public void testGetContentCacheController() {
    assertThat(underTest.getContentCacheController(), is(contentCacheController));
  }

  @Test
  public void testGetMetadataCacheController() {
    assertThat(underTest.getMetadataCacheController(), is(metadataCacheController));
  }

  @Test
  public void testGetContentCacheControllerViaGet() {
    assertThat(underTest.get(CacheControllerHolder.CONTENT), is(contentCacheController));
  }

  @Test
  public void testGetMetadataCacheControllerViaGet() {
    assertThat(underTest.get(CacheControllerHolder.METADATA), is(metadataCacheController));
  }

  @Test
  public void testGetUnknownCacheControllerViaGet() {
    assertThat(underTest.get(TEST), is(nullValue()));
  }

  @Test
  public void testGetContentCacheControllerViaRequire() {
    assertThat(underTest.require(CacheControllerHolder.CONTENT), is(contentCacheController));
  }

  @Test
  public void testGetMetadataCacheControllerViaRequire() {
    assertThat(underTest.require(CacheControllerHolder.METADATA), is(metadataCacheController));
  }

  @Test
  public void testGetUnknownCacheControllerViaRequire() {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      underTest.require(TEST);
    });
    assertThat(exception.getMessage().contains(TEST.value()), is(true));
  }

  @Test
  public void testConcurrentAccessWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Alternate between different operations to test thread safety
            switch (taskId % 4) {
              case 0:
                // Get content controller
                CacheController content = underTest.getContentCacheController();
                if (content != contentCacheController) {
                  errorCount.incrementAndGet();
                }
                break;
              case 1:
                // Get metadata controller
                CacheController metadata = underTest.getMetadataCacheController();
                if (metadata != metadataCacheController) {
                  errorCount.incrementAndGet();
                }
                break;
              case 2:
                // Get via type
                CacheController byType = underTest.get(CacheControllerHolder.CONTENT);
                if (byType != contentCacheController) {
                  errorCount.incrementAndGet();
                }
                break;
              case 3:
                // Require via type
                CacheController required = underTest.require(CacheControllerHolder.METADATA);
                if (required != metadataCacheController) {
                  errorCount.incrementAndGet();
                }
                break;
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify no errors occurred
      assertThat("No errors should occur during concurrent access", errorCount.get(), is(0));
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}