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

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.test.VirtualThreadTestGroup;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

/**
 * Tests for {@link MavenMatcherSupport} when executed with Java 21's Virtual Threads.
 * 
 * @since 3.60
 */
@VirtualThreadTestGroup
public class MavenMatcherSupportVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 100;
  private static final int TIMEOUT_SECONDS = 10;
  
  /**
   * Tests that the {@link MavenMatcherSupport#withHashes} utility functions correctly
   * when executed with Java 21's Virtual Threads.
   */
  @Test
  public void equalsWithHashesInVirtualThreads() throws Exception {
    Predicate<String> pred = MavenMatcherSupport.withHashes("/some-path.txt"::equals);
    
    // Basic functionality test
    assertThat(pred.apply("/some-path.txt"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.sha1"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.md5"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.sha256"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.sha512"), equalTo(true));

    assertThat(pred.apply("some-path.txt"), equalTo(false));
    assertThat(pred.apply("/some-path.txt.crc"), equalTo(false));
    assertThat(pred.apply("/some-path.tx"), equalTo(false));
    assertThat(pred.apply("/other-path.txt"), equalTo(false));
    
    // Test with multiple virtual threads
    testWithMultipleVirtualThreads(pred);
  }

  /**
   * Tests that the {@link MavenMatcherSupport#withHashes} utility with endsWith predicate
   * functions correctly when executed with Java 21's Virtual Threads.
   */
  @Test
  public void endsWithWithHashesInVirtualThreads() throws Exception {
    Predicate<String> pred = MavenMatcherSupport.withHashes((String input) -> input.endsWith("/some-path.txt"));

    // Basic functionality test
    assertThat(pred.apply("/some/prefix/some-path.txt"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.sha1"), equalTo(true));
    assertThat(pred.apply("some/prefix/some-path.txt.md5"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.sha256"), equalTo(true));
    assertThat(pred.apply("/some-path.txt.sha512"), equalTo(true));

    assertThat(pred.apply("some-path.txt"), equalTo(false));
    assertThat(pred.apply("/some-path.txt.crc"), equalTo(false));
    assertThat(pred.apply("/some-path.txt.tx"), equalTo(false));
    assertThat(pred.apply("/other-path.txt"), equalTo(false));
    
    // Test with multiple virtual threads
    testWithMultipleVirtualThreads(pred);
  }
  
  /**
   * Tests the given predicate with multiple concurrent Virtual Threads to ensure
   * thread safety and correct behavior under high concurrency.
   */
  private void testWithMultipleVirtualThreads(final Predicate<String> predicate) throws Exception {
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    final AtomicBoolean testPassed = new AtomicBoolean(true);
    
    // Create a list of test paths that should match and not match
    final List<String> shouldMatch = List.of(
        "/some-path.txt",
        "/some-path.txt.sha1",
        "/some-path.txt.md5",
        "/some-path.txt.sha256",
        "/some-path.txt.sha512",
        "/some/prefix/some-path.txt"
    );
    
    final List<String> shouldNotMatch = List.of(
        "some-path.txt",
        "/some-path.txt.crc",
        "/some-path.tx",
        "/other-path.txt"
    );
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int threadNum = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Test paths that should match
            for (String path : shouldMatch) {
              if (!predicate.apply(path)) {
                log.error("Thread {}: Path '{}' should match but doesn't", threadNum, path);
                testPassed.set(false);
              }
            }
            
            // Test paths that should not match
            for (String path : shouldNotMatch) {
              if (predicate.apply(path)) {
                log.error("Thread {}: Path '{}' should not match but does", threadNum, path);
                testPassed.set(false);
              }
            }
          }
          catch (Exception e) {
            log.error("Thread {} failed with exception", threadNum, e);
            testPassed.set(false);
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
      assertThat("All virtual threads completed in time", completed, equalTo(true));
      assertThat("All predicate tests passed in all virtual threads", testPassed.get(), equalTo(true));
    }
  }
}