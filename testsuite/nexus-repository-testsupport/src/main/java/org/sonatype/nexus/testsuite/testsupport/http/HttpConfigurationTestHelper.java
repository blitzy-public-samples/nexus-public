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
package org.sonatype.nexus.testsuite.testsupport.http;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.inject.Provider;

import org.sonatype.nexus.common.entity.EntityHelper;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.httpclient.config.ConnectionConfiguration;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyConfiguration;
import org.sonatype.nexus.httpclient.config.ProxyServerConfiguration;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper class for HTTP client configuration in tests.
 * <p>
 * This implementation leverages Java 21 virtual threads for improved concurrency
 * when performing HTTP client reconfiguration operations. Virtual threads provide
 * lightweight concurrency for I/O-bound operations without the overhead of traditional
 * platform threads, allowing for more efficient resource utilization.
 * <p>
 * Each configuration operation is executed on a virtual thread and waits for completion
 * to ensure the configuration is properly applied before returning to the caller.
 *
 * @since 3.60
 */
public class HttpConfigurationTestHelper
{
  private final Provider<HttpClientManager> httpClientManagerProvider;
  
  /**
   * Virtual thread executor for handling I/O-bound operations.
   * Java 21 virtual threads are lightweight and efficient for I/O operations,
   * allowing thousands of concurrent operations with minimal resource usage.
   */
  private final ExecutorService executor;
  
  /**
   * Shutdown hook to close the executor service when the JVM exits.
   */
  private final Thread shutdownHook = new Thread(() -> {
    if (executor != null && !executor.isShutdown()) {
      executor.close(); // Using close() instead of shutdown() for virtual threads
    }
  });

  /**
   * Constructor.
   *
   * @param httpClientManagerProvider the HTTP client manager provider
   */
  public HttpConfigurationTestHelper(Provider<HttpClientManager> httpClientManagerProvider) {
    checkNotNull(httpClientManagerProvider);
    this.httpClientManagerProvider = httpClientManagerProvider;
    // Create a virtual thread executor for I/O-bound operations
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    // Register shutdown hook to ensure executor is closed
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  /**
   * Enables proxy configuration for both HTTP and HTTPS.
   *
   * @param proxyHost the proxy host
   * @param proxyPort the proxy port
   */
  public void enableProxy(String proxyHost, int proxyPort) {
    checkProxyArgs(proxyHost, proxyPort);
    
    // Use virtual thread for this I/O-bound operation
    Future<?> future = executor.submit(() -> {
      HttpClientConfiguration httpConfig = getCurrentConfig();
      enableHttpAndHttpsProxy(httpConfig, proxyHost, proxyPort);
      setConfig(httpConfig);
      return null; // Explicit return to avoid Future<Void> warnings
    });
    
    // Wait for completion to ensure configuration is applied
    try {
      future.get();
    } 
    catch (Exception e) {
      throw new RuntimeException("Failed to enable proxy configuration", e);
    }
  }

  /**
   * Disables proxy configuration for both HTTP and HTTPS.
   */
  public void disableProxy() {
    // Use virtual thread for this I/O-bound operation
    Future<?> future = executor.submit(() -> {
      HttpClientConfiguration httpConfig = getCurrentConfig();
      disableHttpAndHttpsProxy(httpConfig);
      setConfig(httpConfig);
      return null; // Explicit return to avoid Future<Void> warnings
    });
    
    // Wait for completion to ensure configuration is applied
    try {
      future.get();
    } 
    catch (Exception e) {
      throw new RuntimeException("Failed to disable proxy configuration", e);
    }
  }

  /**
   * Enables proxy configuration with a user agent suffix.
   *
   * @param proxyHost the proxy host
   * @param proxyPort the proxy port
   * @param userAgentSuffix the user agent suffix
   */
  public void enableProxyWithUserAgentSuffix(String proxyHost, int proxyPort, String userAgentSuffix) {
    checkProxyArgs(proxyHost, proxyPort);
    checkUserAgentSuffixArg(userAgentSuffix);

    // Use virtual thread for this I/O-bound operation
    Future<?> future = executor.submit(() -> {
      HttpClientConfiguration httpConfig = getCurrentConfig();
      enableHttpAndHttpsProxy(httpConfig, proxyHost, proxyPort);
      setGlobalUserAgentSuffix(httpConfig, userAgentSuffix);
      setConfig(httpConfig);
      return null; // Explicit return to avoid Future<Void> warnings
    });
    
    // Wait for completion to ensure configuration is applied
    try {
      future.get();
    } 
    catch (Exception e) {
      throw new RuntimeException("Failed to enable proxy with user agent suffix", e);
    }
  }

  /**
   * Disables proxy configuration and clears the user agent suffix.
   */
  public void disableProxyAndClearUserAgentSuffix() {
    // Use virtual thread for this I/O-bound operation
    Future<?> future = executor.submit(() -> {
      HttpClientConfiguration httpConfig = getCurrentConfig();
      disableHttpAndHttpsProxy(httpConfig);
      removeGlobalUserAgentSuffix(httpConfig);
      setConfig(httpConfig);
      return null; // Explicit return to avoid Future<Void> warnings
    });
    
    // Wait for completion to ensure configuration is applied
    try {
      future.get();
    } 
    catch (Exception e) {
      throw new RuntimeException("Failed to disable proxy and clear user agent suffix", e);
    }
  }

  /**
   * Sets the global user agent suffix.
   *
   * @param userAgentSuffix the user agent suffix
   */
  public void setGlobalUserAgentSuffix(String userAgentSuffix) {
    checkUserAgentSuffixArg(userAgentSuffix);
    
    // Use virtual thread for this I/O-bound operation
    Future<?> future = executor.submit(() -> {
      HttpClientConfiguration httpConfig = getCurrentConfig();
      setGlobalUserAgentSuffix(httpConfig, userAgentSuffix);
      setConfig(httpConfig);
      return null; // Explicit return to avoid Future<Void> warnings
    });
    
    // Wait for completion to ensure configuration is applied
    try {
      future.get();
    } 
    catch (Exception e) {
      throw new RuntimeException("Failed to set global user agent suffix", e);
    }
  }

  /**
   * Sets the HTTP client configuration.
   *
   * @param config the HTTP client configuration
   */
  private void setConfig(HttpClientConfiguration config) {
    provideHttpClientManager().setConfiguration(config);
  }

  /**
   * Gets the current HTTP client configuration.
   *
   * @return the current HTTP client configuration
   */
  private HttpClientConfiguration getCurrentConfig() {
    // Clear entity metadata to avoid data/serialization conflicts
    HttpClientConfiguration httpConfig = provideHttpClientManager().getConfiguration().copy();
    EntityHelper.clearMetadata(httpConfig);
    return httpConfig;
  }

  /**
   * Gets the HTTP client manager from the provider.
   *
   * @return the HTTP client manager
   */
  private HttpClientManager provideHttpClientManager() {
    HttpClientManager manager = httpClientManagerProvider.get();
    checkNotNull(manager);
    return manager;
  }

  /**
   * Validates the user agent suffix argument.
   *
   * @param suffix the user agent suffix
   */
  private void checkUserAgentSuffixArg(String suffix) {
    checkArgument(!Strings2.isBlank(suffix), "User agent suffix must be a non-blank string.");
  }

  /**
   * Validates the proxy host and port arguments.
   *
   * @param proxyHost the proxy host
   * @param proxyPort the proxy port
   */
  private void checkProxyArgs(String proxyHost, int proxyPort) {
    checkArgument(!Strings2.isBlank(proxyHost), "Proxy host must be a non-blank string.");
    checkArgument(proxyPort > 0, "Proxy port must be greater than zero.");
  }

  /**
   * Enables HTTP and HTTPS proxy configuration.
   *
   * @param httpConfig the HTTP client configuration
   * @param proxyHost the proxy host
   * @param proxyPort the proxy port
   */
  private void enableHttpAndHttpsProxy(HttpClientConfiguration httpConfig, String proxyHost, int proxyPort) {
    ProxyServerConfiguration server = new ProxyServerConfiguration();
    server.setHost(proxyHost);
    server.setPort(proxyPort);
    server.setEnabled(true);

    ProxyConfiguration config = new ProxyConfiguration();
    config.setHttp(server);
    config.setHttps(server);

    httpConfig.setProxy(config);
  }

  /**
   * Disables HTTP and HTTPS proxy configuration.
   *
   * @param httpConfig the HTTP client configuration
   */
  private void disableHttpAndHttpsProxy(HttpClientConfiguration httpConfig) {
    httpConfig.setProxy(null);
  }

  /**
   * Sets the global user agent suffix in the HTTP client configuration.
   *
   * @param httpConfig the HTTP client configuration
   * @param suffix the user agent suffix
   */
  private void setGlobalUserAgentSuffix(HttpClientConfiguration httpConfig, String suffix) {
    ConnectionConfiguration conn = httpConfig.getConnection();
    if (conn == null) {
      conn = new ConnectionConfiguration();
    }
    conn.setUserAgentSuffix(suffix);

    httpConfig.setConnection(conn);
  }

  /**
   * Removes the global user agent suffix from the HTTP client configuration.
   *
   * @param httpConfig the HTTP client configuration
   */
  private void removeGlobalUserAgentSuffix(HttpClientConfiguration httpConfig) {
    ConnectionConfiguration conn = httpConfig.getConnection();
    if (conn == null) {
      conn = new ConnectionConfiguration();
    }
    conn.setUserAgentSuffix(null);

    httpConfig.setConnection(conn);
  }
}