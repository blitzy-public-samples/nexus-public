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
package org.sonatype.nexus.repository.maven.internal.matcher;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests {@link MavenNx2MetaFilesMatcher} with Java 21 Virtual Threads.
 * 
 * This test validates that the matcher functions correctly when executed concurrently
 * across multiple Virtual Threads, ensuring thread safety and correct behavior in a
 * highly concurrent environment.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class MavenNx2MetaFilesMatcherVirtualThreadTest
    extends VirtualThreadTestSupport
{
  @Mock
  MavenPathParser mavenPathParser;

  @Mock
  MavenPath mavenPath;

  @Mock
  Context context;

  @Mock
  Request request;

  MavenNx2MetaFilesMatcher underTest;

  @BeforeEach
  public void setup() {
    when(mavenPathParser.parsePath(any())).thenReturn(mavenPath);
    when(context.getRequest()).thenReturn(request);
    when(context.getAttributes()).thenReturn(new AttributesMap());

    underTest = new MavenNx2MetaFilesMatcher(mavenPathParser);
  }

  /**
   * Tests that the matcher correctly identifies meta files when executed on a Virtual Thread.
   */
  @Test
  public void testMatchesOnVirtualThread() throws Exception {
    supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertCurrentThreadIsVirtual();
      
      // Test matching paths
      when(request.getPath()).thenReturn("/.meta/prefixes.txt");
      assertThat(underTest.matches(context), is(true));
      
      when(request.getPath()).thenReturn("/.meta/somethingelse.txt");
      assertThat(underTest.matches(context), is(true));
      
      return null;
    });
  }

  /**
   * Tests that the matcher correctly rejects non-meta files when executed on a Virtual Thread.
   */
  @Test
  public void testNonMatchesOnVirtualThread() throws Exception {
    supplyFromVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertCurrentThreadIsVirtual();
      
      // Test non-matching path
      when(request.getPath()).thenReturn("/real/content.txt");
      assertThat(underTest.matches(context), is(false));
      
      return null;
    });
  }

  /**
   * Tests that the matcher behaves correctly when executed concurrently across multiple Virtual Threads.
   * This validates thread safety and consistent behavior in a highly concurrent environment.
   */
  @Test
  public void testConcurrentMatchingWithVirtualThreads() throws Exception {
    // Number of concurrent threads to test with
    int threadCount = 1000;
    
    // Counters for tracking results
    AtomicInteger matchCount = new AtomicInteger(0);
    AtomicInteger nonMatchCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Latch for synchronizing thread completion
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Create a Virtual Thread executor
    try (ExecutorService executor = createVirtualThreadExecutorService()) {
      // Submit tasks to test matching and non-matching paths concurrently
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between matching and non-matching paths
            if (index % 2 == 0) {
              when(request.getPath()).thenReturn("/.meta/prefixes.txt");
              if (underTest.matches(context)) {
                matchCount.incrementAndGet();
              }
            } else {
              when(request.getPath()).thenReturn("/real/content.txt");
              if (!underTest.matches(context)) {
                nonMatchCount.incrementAndGet();
              }
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error in virtual thread test", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      latch.await();
    }
    
    // Verify results
    assertEquals(0, errorCount.get(), "No errors should occur during concurrent execution");
    assertEquals(threadCount / 2, matchCount.get(), "Half of the paths should match");
    assertEquals(threadCount / 2, nonMatchCount.get(), "Half of the paths should not match");
  }
}