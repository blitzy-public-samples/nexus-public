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

package org.sonatype.nexus.content.maven.internal.recipe;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.repository.maven.MavenProxyRequestHeaderSupport;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.validation.ConstraintViolationFactory;

import org.apache.http.client.methods.HttpRequestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link MavenProxyFacet} with Java 21 compatibility.
 * 
 * This test class has been updated to use JUnit Jupiter (JUnit 5) and Mockito 4.11.0
 * to ensure compatibility with Java 21.
 */
@ExtendWith(MockitoExtension.class)
class MavenProxyFacetTest
    extends TestSupport
{
  @Mock
  private ConstraintViolationFactory constraintViolationFactory;

  @Mock
  private ApplicationVersion applicationVersion;

  @Mock
  private MavenProxyRequestHeaderSupport mavenProxyRequestHeaderSupport;

  private MavenProxyFacet underTest;

  @BeforeEach
  void setUp() {
    this.underTest = new MavenProxyFacet(constraintViolationFactory
        , mavenProxyRequestHeaderSupport);
    when(applicationVersion.getEdition()).thenReturn("edition");
  }

  @Test
  void testNonMavenCentralHostAndVerifyRequestHeader() throws URISyntaxException {
    URI uri = new URI("schema", "host", "/path/test", "fragment");
    Context context = mock(Context.class);

    HttpRequestBase request = underTest.buildFetchHttpRequest(uri, context);
    assertEquals(0, request.getAllHeaders().length, "Non-Maven Central host should not have headers added");
  }

  @Test
  void testMavenCentralHostAndVerifyRequestHeader() throws URISyntaxException {
    URI uri = new URI("schema", "repo1.maven.org", "/path/test", "fragment");
    Context context = mock(Context.class);
    HttpRequestBase request = underTest.buildFetchHttpRequest(uri, context);
    assertEquals(1, request.getAllHeaders().length, "Maven Central host should have User-Agent header added");
  }
  
  /**
   * Tests the MavenProxyFacet with concurrent requests using Java 21 Virtual Threads.
   * This test validates that the buildFetchHttpRequest method can handle multiple concurrent
   * requests efficiently using the new lightweight threading model in Java 21.
   */
  @Test
  void testConcurrentRequestsWithVirtualThreads() throws Exception {
    // Use Java 21's Virtual Thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int requestCount = 100;
    CountDownLatch latch = new CountDownLatch(requestCount);
    AtomicInteger mavenCentralCount = new AtomicInteger(0);
    AtomicInteger otherHostCount = new AtomicInteger(0);
    
    // Set up the User-Agent header for Maven Central requests
    when(mavenProxyRequestHeaderSupport.getUserAgentForAnalytics()).thenReturn("Nexus-Repository-Maven/1.0");
    
    assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
      try {
        // Submit multiple concurrent tasks using virtual threads
        for (int i = 0; i < requestCount; i++) {
          final int index = i;
          executor.submit(() -> {
            try {
              // Alternate between Maven Central and other hosts
              URI uri;
              if (index % 2 == 0) {
                uri = new URI("schema", "repo1.maven.org", "/path/test" + index, "fragment");
                Context context = mock(Context.class);
                HttpRequestBase request = underTest.buildFetchHttpRequest(uri, context);
                if (request.getAllHeaders().length == 1) {
                  mavenCentralCount.incrementAndGet();
                }
              } else {
                uri = new URI("schema", "host" + index, "/path/test", "fragment");
                Context context = mock(Context.class);
                HttpRequestBase request = underTest.buildFetchHttpRequest(uri, context);
                if (request.getAllHeaders().length == 0) {
                  otherHostCount.incrementAndGet();
                }
              }
            } catch (Exception e) {
              // Log any exceptions
              log.error("Error in virtual thread task", e);
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete
        latch.await(3, TimeUnit.SECONDS);
        
        // Verify results
        assertEquals(requestCount / 2, mavenCentralCount.get(), 
            "All Maven Central requests should have headers added");
        assertEquals(requestCount / 2, otherHostCount.get(), 
            "All non-Maven Central requests should have no headers added");
      } finally {
        executor.shutdown();
      }
    });
  }
}
