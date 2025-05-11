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
package org.sonatype.nexus.common.io;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.io.CooperationFactorySupport.Config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Tests for {@link CooperatingFuture}.
 */
@ExtendWith(MockitoExtension.class)
public class CooperatingFutureTest
    extends TestSupport
{
  @Mock
  Config config;

  @Test
  @DisplayName("Download timeouts are staggered appropriately")
  public void downloadTimeoutsAreStaggered() {
    CooperatingFuture<String> cooperatingFuture = new CooperatingFuture<>("testKey", config);

    Random random = new Random();
    long[] downloadTimeMillis = new long[10];
    long expectedGap = 200;

    downloadTimeMillis[0] = System.currentTimeMillis(); // first download
    for (int i = 1; i < downloadTimeMillis.length; i++) {

      // random sleep representing some client-side work
      LockSupport.parkNanos(Duration.ofMillis(random.nextInt((int) expectedGap)).toNanos());

      // staggered sleep should bring us close to the expected gap
      LockSupport.parkNanos(cooperatingFuture.staggerTimeout(Duration.ofMillis(expectedGap)).toNanos());

      downloadTimeMillis[i] = System.currentTimeMillis(); // next download
    }

    for (int i = 1; i < downloadTimeMillis.length; i++) {
      long actualGap = downloadTimeMillis[i] - downloadTimeMillis[i - 1];
      assertThat(actualGap, allOf(greaterThanOrEqualTo(expectedGap - 10), lessThanOrEqualTo(expectedGap + 50)));
    }
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  @DisplayName("CooperatingFuture works with virtual threads")
  public void cooperatingFutureWorksWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Test that CooperatingFuture can be used from virtual threads
      int threadCount = 5;
      AtomicBoolean success = new AtomicBoolean(true);
      
      // Create multiple cooperating futures and access them from virtual threads
      CooperatingFuture<String> cooperatingFuture = new CooperatingFuture<>("testKey", config);
      
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Test staggerTimeout functionality from virtual threads
            Duration timeout = Duration.ofMillis(100);
            Duration staggered = cooperatingFuture.staggerTimeout(timeout);
            
            // Verify staggered timeout is reasonable
            if (staggered.toMillis() < 0 || staggered.toMillis() > timeout.toMillis() * 2) {
              log.error("Unexpected staggered timeout: {}ms", staggered.toMillis());
              success.set(false);
            }
            
            // Use the timeout
            LockSupport.parkNanos(staggered.toNanos());
          } catch (Exception e) {
            log.error("Error in virtual thread test", e);
            success.set(false);
          }
        });
      }
      
      // Shutdown and wait for completion
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
      
      // Verify success
      assertThat("All virtual thread operations should succeed", success.get(), org.hamcrest.Matchers.is(true));
    }
  }
}