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
package com.sonatype.nexus.ssl.plugin.internal;

import javax.net.ssl.SSLContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.httpclient.SSLContextSelector;
import org.sonatype.nexus.ssl.TrustStore;

import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link HttpContextAttributeSSLContextSelector}.
 * 
 * Verifies the SSL context selection behavior based on HTTP context attributes.
 * This test class has been updated to use JUnit Jupiter and to demonstrate Java 21 features
 * like Virtual Threads for concurrent SSL context selection.
 * 
 * @since 3.0
 */
@ExtendWith(MockitoExtension.class)
public class HttpContextAttributeSSLContextSelectorTest
    extends TestSupport
{
  @Mock
  private TrustStore trustStore;

  @Mock
  private SSLContext sslContext;

  private HttpContext httpContext;

  private HttpContextAttributeSSLContextSelector sslContextSelector;

  @BeforeEach
  public void setUp() {
    when(trustStore.getSSLContext()).thenReturn(sslContext);
    httpContext = new BasicHttpContext();
    sslContextSelector = new HttpContextAttributeSSLContextSelector(trustStore);
  }

  /**
   * Verifies that when no attribute is set in the HTTP context, the selector returns null.
   */
  @Test
  public void shouldReturnNullWhenAttributeIsNull() {
    assertThat(sslContextSelector.select(httpContext), is(nullValue()));
  }

  /**
   * Verifies that when the attribute is explicitly set to false, the selector returns null.
   */
  @Test
  public void shouldReturnNullWhenAttributeIsFalse() {
    httpContext.setAttribute(SSLContextSelector.USE_TRUST_STORE, false);
    assertThat(sslContextSelector.select(httpContext), is(nullValue()));
  }

  /**
   * Verifies that when the attribute is set to true, the selector returns the SSL context from the trust store.
   */
  @Test
  public void shouldReturnSslContextWhenAttributeIsTrue() {
    httpContext.setAttribute(SSLContextSelector.USE_TRUST_STORE, true);
    assertThat(sslContextSelector.select(httpContext), is(sslContext));
  }
  
  /**
   * Demonstrates the use of Java 21 Virtual Threads to concurrently select SSL contexts.
   * This test creates multiple HTTP contexts with different attribute values and verifies
   * that the selector correctly handles concurrent access from virtual threads.
   */
  @Test
  public void shouldHandleConcurrentSelectionWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<SSLContext> results = new ArrayList<>();
    
    // Create an executor service with virtual threads (Java 21 feature)
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to be executed by virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create a new context for each thread
            HttpContext threadContext = new BasicHttpContext();
            
            // Set different attribute values based on the thread index
            if (index % 3 == 0) {
              // No attribute set
            }
            else if (index % 3 == 1) {
              threadContext.setAttribute(SSLContextSelector.USE_TRUST_STORE, false);
            }
            else {
              threadContext.setAttribute(SSLContextSelector.USE_TRUST_STORE, true);
            }
            
            // Select the SSL context
            SSLContext selectedContext = sslContextSelector.select(threadContext);
            
            // Record the result
            synchronized (results) {
              results.add(selectedContext);
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      completionLatch.await(5, TimeUnit.SECONDS);
    }
    
    // Verify results
    assertThat(results, hasSize(threadCount));
    
    // Count the number of null and non-null results
    long nullCount = results.stream().filter(ctx -> ctx == null).count();
    long sslContextCount = results.stream().filter(ctx -> ctx == sslContext).count();
    
    // Verify the distribution of results
    // Approximately 2/3 should be null (no attribute or false) and 1/3 should be the SSL context (true)
    assertThat(nullCount + sslContextCount, is((long) threadCount));
    assertThat(sslContextCount > 0, is(true));
    assertThat(nullCount > 0, is(true));
  }
}
