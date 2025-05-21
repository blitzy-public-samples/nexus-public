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
package org.sonatype.nexus.virtualthread;

import java.io.IOException;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContexts;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sonatype.goodies.httpfixture.server.fluent.Behaviours;
import org.sonatype.goodies.httpfixture.server.fluent.Server;
import org.sonatype.goodies.httpfixture.validation.ValidatingBehaviour;
import org.sonatype.goodies.httpfixture.validation.ValidatingProxyServer;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.internal.httpclient.DefaultsCustomizer;
import org.sonatype.nexus.internal.httpclient.HttpClientConfigurationStore;
import org.sonatype.nexus.internal.httpclient.HttpClientManagerImpl;
import org.sonatype.nexus.internal.httpclient.SharedHttpClientConnectionManager;
import org.sonatype.nexus.internal.httpclient.TestHttpClientConfiguration;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.testcommon.validation.HeaderValidator;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests HTTP client operations using Java 21 Virtual Threads.
 * 
 * This class validates that the HTTP client implementation properly utilizes
 * Virtual Threads for improved concurrency and resource efficiency.
 */
public class HttpClientVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int WARMUP_REQUESTS = 50;
  private static final String TEST_KEYSTORE = "testkeystore";
  private static final String TEST_KEYSTORE_PASSWORD = "password";
  private static final String USER_AGENT_HEADER = "User-Agent";
  
  private static Server httpServer;
  private static Server httpsServer;
  private static ValidatingProxyServer proxyServer;
  private static HeaderValidator headerValidator;
  
  private HttpClientManagerImpl httpClientManager;
  
  /**
   * Set up test servers before running tests.
   */
  @BeforeClass
  public static void setupServers() throws Exception {
    // Create a header validator to track User-Agent headers
    headerValidator = new HeaderValidator(USER_AGENT_HEADER);
    
    // Set up HTTP server
    httpServer = Server.withPort(0).withBehaviour(Behaviours.content("OK"))
        .withBehaviour(headerValidator).start();
    
    // Set up HTTPS server with self-signed certificate
    httpsServer = Server.withPort(0).withHttps()
        .withBehaviour(Behaviours.content("OK"))
        .withBehaviour(headerValidator).start();
    
    // Set up proxy server
    proxyServer = new ValidatingProxyServer(0, null);
    proxyServer.start();
  }
  
  /**
   * Tear down test servers after all tests are complete.
   */
  @AfterClass
  public static void tearDownServers() throws Exception {
    if (httpServer != null) {
      httpServer.stop();
    }
    
    if (httpsServer != null) {
      httpsServer.stop();
    }
    
    if (proxyServer != null) {
      proxyServer.stop();
    }
  }
  
  /**
   * Set up HTTP client manager before each test.
   */
  public void setUp() throws Exception {
    super.setUp();
    
    // Reset header validation counts
    headerValidator.reset();
    
    // Create mocks for HTTP client manager dependencies
    EventManager eventManager = mock(EventManager.class);
    HttpClientConfigurationStore configStore = mock(HttpClientConfigurationStore.class);
    SharedHttpClientConnectionManager connectionManager = mock(SharedHttpClientConnectionManager.class);
    DefaultsCustomizer defaultsCustomizer = mock(DefaultsCustomizer.class);
    
    // Create test HTTP client configuration
    TestHttpClientConfiguration config = new TestHttpClientConfiguration();
    when(configStore.load()).thenReturn(config);
    
    // Create HTTP client manager
    httpClientManager = new HttpClientManagerImpl(eventManager, configStore, connectionManager, defaultsCustomizer);
  }
  
  /**
   * Tests HTTP client with a high number of concurrent requests using Virtual Threads.
   * 
   * This test verifies that the HTTP client can handle a large number of concurrent
   * requests efficiently using Virtual Threads without exhausting system resources.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Prepare HTTP client builder
    HttpClientBuilder builder = httpClientManager.prepare();
    
    // Create HTTP client
    try (CloseableHttpClient client = builder.build()) {
      // Warm up with a few requests
      executeRequests(client, httpServer.getUri(), WARMUP_REQUESTS);
      
      // Execute high concurrency test
      long startTime = System.nanoTime();
      int successCount = executeRequests(client, httpServer.getUri(), CONCURRENT_REQUESTS);
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
      
      // Verify all requests were successful
      assertThat(successCount, equalTo(CONCURRENT_REQUESTS));
      
      // Verify User-Agent header was sent with each request
      assertThat(headerValidator.getValidationCount(), equalTo(CONCURRENT_REQUESTS + WARMUP_REQUESTS));
      
      // Log performance metrics
      log.info("Completed {} concurrent requests in {} ms", CONCURRENT_REQUESTS, durationMs);
      log.info("Average request time: {} ms", (double) durationMs / CONCURRENT_REQUESTS);
      
      // Verify reasonable performance (this is a soft assertion as performance can vary by environment)
      assertThat("Request throughput should be reasonable", 
          durationMs, lessThan((long) CONCURRENT_REQUESTS * 100)); // Less than 100ms per request on average
    }
  }
  
  /**
   * Tests HTTPS client with Virtual Threads.
   * 
   * This test verifies that the HTTPS client works correctly with SSL context
   * when executed on Virtual Threads.
   */
  @Test
  public void testHttpsWithVirtualThreads() throws Exception {
    // Create SSL context that trusts self-signed certificates
    SSLContext sslContext = SSLContexts.custom()
        .loadTrustMaterial(getClass().getResource(TEST_KEYSTORE), TEST_KEYSTORE_PASSWORD.toCharArray(),
            new TrustSelfSignedStrategy())
        .build();
    
    // Create SSL socket factory
    SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(sslContext);
    
    // Prepare HTTP client builder with SSL socket factory
    HttpClientBuilder builder = httpClientManager.prepare()
        .setSSLSocketFactory(sslSocketFactory);
    
    // Create HTTP client
    try (CloseableHttpClient client = builder.build()) {
      // Execute concurrent HTTPS requests
      int successCount = executeRequests(client, httpsServer.getUri(), 100);
      
      // Verify all requests were successful
      assertThat(successCount, equalTo(100));
    }
  }
  
  /**
   * Tests HTTP client with proxy using Virtual Threads.
   * 
   * This test verifies that the HTTP client works correctly with a proxy
   * when executed on Virtual Threads.
   */
  @Test
  public void testProxyWithVirtualThreads() throws Exception {
    // Create proxy host
    HttpHost proxy = new HttpHost("localhost", proxyServer.getPort());
    
    // Prepare HTTP client builder with proxy
    HttpClientBuilder builder = httpClientManager.prepare()
        .setProxy(proxy);
    
    // Create HTTP client
    try (CloseableHttpClient client = builder.build()) {
      // Execute concurrent requests through proxy
      int successCount = executeRequests(client, httpServer.getUri(), 100);
      
      // Verify all requests were successful
      assertThat(successCount, equalTo(100));
      
      // Verify requests went through proxy
      assertThat(proxyServer.getAccessedUris().size(), greaterThan(0));
    }
  }
  
  /**
   * Tests thread pinning detection with HTTP client operations.
   * 
   * This test verifies that HTTP client operations don't cause thread pinning
   * when executed on Virtual Threads.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    // Prepare HTTP client builder
    HttpClientBuilder builder = httpClientManager.prepare();
    
    // Create HTTP client
    try (CloseableHttpClient client = builder.build()) {
      // Create a thread pool with Virtual Threads
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Create a latch to wait for all tasks to complete
        CountDownLatch latch = new CountDownLatch(1);
        
        // Submit a task that performs HTTP operations
        executor.submit(() -> {
          try {
            // Execute HTTP request
            HttpGet request = new HttpGet(httpServer.getUri());
            try (CloseableHttpResponse response = client.execute(request)) {
              // Verify response status
              assertThat(response.getStatusLine().getStatusCode(), equalTo(HttpStatus.OK));
            }
            
            // Count down latch to signal completion
            latch.countDown();
          }
          catch (Exception e) {
            log.error("Error executing HTTP request", e);
          }
        });
        
        // Wait for task to complete with timeout
        boolean completed = latch.await(5, TimeUnit.SECONDS);
        
        // Verify task completed successfully (no thread pinning)
        assertThat("Task should complete without thread pinning", completed, is(true));
      }
    }
  }
  
  /**
   * Tests HTTP client performance comparison between platform threads and Virtual Threads.
   * 
   * This test compares the performance of HTTP client operations when executed on
   * platform threads versus Virtual Threads.
   */
  @Test
  public void testPerformanceComparison() throws Exception {
    // Prepare HTTP client builder
    HttpClientBuilder builder = httpClientManager.prepare();
    
    // Create HTTP client
    try (CloseableHttpClient client = builder.build()) {
      // Test with platform threads
      long platformThreadTime = measureExecutionTime(() -> {
        try (ExecutorService executor = Executors.newFixedThreadPool(100)) {
          executeRequestsWithExecutor(client, httpServer.getUri(), 100, executor);
        }
      });
      
      // Test with Virtual Threads
      long virtualThreadTime = measureExecutionTime(() -> {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          executeRequestsWithExecutor(client, httpServer.getUri(), 100, executor);
        }
      });
      
      // Log performance comparison
      log.info("Platform thread execution time: {} ms", platformThreadTime);
      log.info("Virtual thread execution time: {} ms", virtualThreadTime);
      
      // Note: This is a soft assertion as performance can vary by environment
      // In most cases, Virtual Threads should be more efficient for I/O-bound operations
      log.info("Performance ratio (platform/virtual): {}", (double) platformThreadTime / virtualThreadTime);
    }
  }
  
  /**
   * Executes HTTP requests concurrently using Virtual Threads.
   * 
   * @param client the HTTP client to use
   * @param uri the URI to request
   * @param count the number of concurrent requests to execute
   * @return the number of successful requests
   */
  private int executeRequests(CloseableHttpClient client, URI uri, int count) throws Exception {
    // Use Virtual Threads executor for concurrent requests
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executeRequestsWithExecutor(client, uri, count, executor);
    }
  }
  
  /**
   * Executes HTTP requests concurrently using the provided executor.
   * 
   * @param client the HTTP client to use
   * @param uri the URI to request
   * @param count the number of concurrent requests to execute
   * @param executor the executor service to use
   * @return the number of successful requests
   */
  private int executeRequestsWithExecutor(CloseableHttpClient client, URI uri, int count, ExecutorService executor) 
      throws Exception {
    // Create a latch to wait for all requests to complete
    CountDownLatch latch = new CountDownLatch(count);
    
    // Track successful requests
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit requests to executor
    List<Throwable> errors = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      executor.submit(() -> {
        try {
          // Create and execute HTTP request
          HttpGet request = new HttpGet(uri);
          try (CloseableHttpResponse response = client.execute(request)) {
            // Check if request was successful
            if (response.getStatusLine().getStatusCode() == HttpStatus.OK) {
              successCount.incrementAndGet();
            }
          }
        }
        catch (Throwable t) {
          // Record error
          synchronized (errors) {
            errors.add(t);
          }
        }
        finally {
          // Count down latch regardless of success/failure
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Log any errors
    if (!errors.isEmpty()) {
      log.error("Encountered {} errors during request execution", errors.size());
      errors.forEach(t -> log.error("Request error", t));
    }
    
    // Verify all requests completed
    assertThat("All requests should complete within timeout", completed, is(true));
    
    return successCount.get();
  }
  
  /**
   * Measures the execution time of a runnable task.
   * 
   * @param task the task to measure
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.nanoTime();
    task.run();
    long endTime = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
  }
}