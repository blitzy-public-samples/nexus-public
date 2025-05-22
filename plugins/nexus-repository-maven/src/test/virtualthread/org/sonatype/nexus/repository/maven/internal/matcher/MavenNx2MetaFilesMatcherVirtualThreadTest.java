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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenNx2MetaFilesMatcher} executed with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
@DisplayName("MavenNx2MetaFilesMatcher with Virtual Threads")
public class MavenNx2MetaFilesMatcherVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final int CONCURRENT_THREADS = 10;
  private static final int TIMEOUT_SECONDS = 5;

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
  void setup() {
    assumeVirtualThreadSupported();
    
    when(mavenPathParser.parsePath(any())).thenReturn(mavenPath);
    when(context.getRequest()).thenReturn(request);
    when(context.getAttributes()).thenReturn(new AttributesMap());

    underTest = new MavenNx2MetaFilesMatcher(mavenPathParser);
  }

  @Test
  @DisplayName("Matches .meta paths correctly")
  void testMatches() {
    when(request.getPath()).thenReturn("/.meta/prefixes.txt");
    assertThat(underTest.matches(context), is(true));
    when(request.getPath()).thenReturn("/.meta/somethingelse.txt");
    assertThat(underTest.matches(context), is(true));
  }

  @Test
  @DisplayName("Does not match non-meta paths")
  void testNonMatches() {
    when(request.getPath()).thenReturn("/real/content.txt");
    assertThat(underTest.matches(context), is(false));
  }

  @Test
  @DisplayName("Handles concurrent matching requests with Virtual Threads")
  void testConcurrentMatching() throws Exception {
    // Setup a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicBoolean allMatchesCorrect = new AtomicBoolean(true);
    
    // Create multiple virtual threads to test concurrent matching
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      Thread.ofVirtual().name("matcher-test-" + index).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Alternate between meta and non-meta paths
          if (index % 2 == 0) {
            when(request.getPath()).thenReturn("/.meta/file" + index + ".txt");
            boolean result = underTest.matches(context);
            if (!result) {
              allMatchesCorrect.set(false);
            }
          } else {
            when(request.getPath()).thenReturn("/content/file" + index + ".txt");
            boolean result = underTest.matches(context);
            if (result) {
              allMatchesCorrect.set(false);
            }
          }
        } 
        catch (Exception e) {
          allMatchesCorrect.set(false);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All threads completed in time", completed, is(true));
    assertThat("All matcher results were correct", allMatchesCorrect.get(), is(true));
  }
  
  @Test
  @DisplayName("Handles high concurrency with Virtual Threads")
  void testHighConcurrencyMatching() throws Exception {
    // Test with a higher number of threads to validate scalability
    int highConcurrencyThreads = 100;
    CountDownLatch completionLatch = new CountDownLatch(highConcurrencyThreads);
    AtomicBoolean allMatchesCorrect = new AtomicBoolean(true);
    
    // Run a high number of concurrent matching operations
    runConcurrently(highConcurrencyThreads, () -> {
      try {
        // Test both meta and non-meta paths
        when(request.getPath()).thenReturn("/.meta/prefixes.txt");
        boolean metaResult = underTest.matches(context);
        
        when(request.getPath()).thenReturn("/content/file.txt");
        boolean nonMetaResult = underTest.matches(context);
        
        if (!metaResult || nonMetaResult) {
          allMatchesCorrect.set(false);
        }
      }
      catch (Exception e) {
        allMatchesCorrect.set(false);
      }
      finally {
        completionLatch.countDown();
      }
    });
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All high concurrency threads completed in time", completed, is(true));
    assertThat("All high concurrency matcher results were correct", allMatchesCorrect.get(), is(true));
  }
}