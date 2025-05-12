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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import org.sonatype.goodies.common.ComponentSupport;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Factory for creating clients of the type {@link FormatClientSupport}.
 * <p>
 * This factory provides methods to create HTTP clients for interacting with Nexus repositories.
 * It supports authentication and configurable timeouts, and leverages Java 21 virtual threads
 * for improved I/O performance.
 *
 * @param <T> the specific type of client to create
 * @since 3.13
 */
public abstract class NexusClientFactory<T extends FormatClientSupport>
    extends ComponentSupport
{
  /** Default timeout for HTTP connections and socket operations */
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20L);

  /** Timeout for connection request from connection manager */
  private static final Duration CONNECTION_REQUEST_TIMEOUT = Duration.ofSeconds(30L);
  
  /** Timeout for virtual thread operations */
  private static final Duration VIRTUAL_THREAD_TIMEOUT = Duration.ofSeconds(10L);

  /**
   * Creates a client with the provided HTTP client, context, and repository URI.
   * 
   * @param httpClient the HTTP client to use for requests
   * @param httpClientContext the HTTP client context with authentication settings
   * @param repositoryBaseUri the base URI of the repository
   * @return a new client instance
   */
  public abstract T createClient(final CloseableHttpClient httpClient,
                                 final HttpClientContext httpClientContext,
                                 final URI repositoryBaseUri);

  /**
   * Creates a client for the specified repository URL with authentication credentials.
   * Uses virtual threads for improved I/O performance when creating and using the client.
   *
   * @param repositoryUrl the URL of the repository
   * @param username the username for authentication
   * @param password the password for authentication
   * @return a new client instance, or null if creation failed
   */
  @Nullable
  public T createClient(final URL repositoryUrl,
                        final String username,
                        final String password)
  {
    checkNotNull(repositoryUrl, "Repository URL cannot be null");
    checkNotNull(username, "Username cannot be null");
    checkNotNull(password, "Password cannot be null");

    // Configure authentication with pattern matching for host validation
    var host = repositoryUrl.getHost();
    if (host == null || host.isEmpty()) {
      log.warn(STR."Invalid repository URL: \{repositoryUrl} - missing host");
      return null;
    }
    
    AuthScope scope = new AuthScope(host, repositoryUrl.getPort() == -1 ? repositoryUrl.getDefaultPort() : repositoryUrl.getPort());
    CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
    credentialsProvider.setCredentials(scope, new UsernamePasswordCredentials(username, password));

    // Configure request settings
    RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();
    requestConfigBuilder.setExpectContinueEnabled(true);

    // Set up auth cache for preemptive authentication
    AuthCache authCache = new BasicAuthCache();
    HttpHost httpHost = new HttpHost(host, repositoryUrl.getPort());
    authCache.put(httpHost, new BasicScheme());

    // Create and configure HTTP client context
    HttpClientContext httpClientContext = HttpClientContext.create();
    httpClientContext.setAuthCache(authCache);
    httpClientContext.setRequestConfig(requestConfigBuilder.build());

    // Configure timeouts
    int defaultTimeoutMillis = (int) DEFAULT_TIMEOUT.toMillis();
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectTimeout(defaultTimeoutMillis)
        .setConnectionRequestTimeout((int) CONNECTION_REQUEST_TIMEOUT.toMillis())
        .setSocketTimeout(defaultTimeoutMillis)
        .build();

    try {
      // Create HTTP client with configured settings
      CloseableHttpClient httpClient = HttpClients.custom()
          .setDefaultCredentialsProvider(credentialsProvider)
          .setDefaultRequestConfig(requestConfig)
          .build();
      
      // Use virtual thread to create the client for improved I/O performance
      var executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        return executor.submit(() -> 
            createClient(httpClient, httpClientContext, repositoryUrl.toURI())
        ).get(VIRTUAL_THREAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      }
      catch (InterruptedException | ExecutionException | TimeoutException e) {
        log.warn(STR."Virtual thread execution failed for \{repositoryUrl}: \{e.getMessage()}", e);
        Thread.currentThread().interrupt(); // Preserve interrupt status
        // Fall back to direct execution if virtual thread fails
        return createClient(httpClient, httpClientContext, repositoryUrl.toURI());
      }
      finally {
        executor.shutdown();
      }
    }
    catch (URISyntaxException e) {
      log.warn(STR."URI exception creating client for \{repositoryUrl}: \{e.getMessage()}", e);
    }
    catch (Exception e) {
      log.warn(STR."Unexpected error creating client for \{repositoryUrl}: \{e.getMessage()}", e);
    }

    return null;
  }
}