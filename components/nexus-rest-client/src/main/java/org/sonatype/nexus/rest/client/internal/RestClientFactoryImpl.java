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
package org.sonatype.nexus.rest.client.internal;

import java.net.URI;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.WebTarget;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.thread.TcclBlock;
import org.sonatype.nexus.httpclient.SSLContextSelector;
import org.sonatype.nexus.rest.client.RestClientConfiguration;
import org.sonatype.nexus.rest.client.RestClientFactory;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import org.apache.http.client.HttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.jboss.resteasy.client.jaxrs.ClientHttpEngine;
import org.jboss.resteasy.client.jaxrs.ProxyBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.cache.CacheLoader.from;

/**
 * REST client factory with Java 21 Virtual Threads support.
 *
 * @since 3.0
 */
@Named("default")
@Singleton
public class RestClientFactoryImpl
    extends ComponentSupport
    implements RestClientFactory
{
  private final LoadingCache<ClassLoader, ClassLoader> bridgeClassLoaderCache =
      CacheBuilder.newBuilder()
          .build(from(
              (loader) -> new BridgeClassLoader(loader, ProxyBuilder.class.getClassLoader())));

  private final Provider<HttpClient> httpClient;
  
  /**
   * Virtual Thread executor for HTTP operations.
   * Uses Java 21's Virtual Threads for high-throughput, non-blocking I/O operations.
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public RestClientFactoryImpl(final Provider<HttpClient> httpClient) {
    this.httpClient = checkNotNull(httpClient);
    log.info("Initialized REST client factory with Java 21 Virtual Threads support");
  }

  @Override
  public Client create(final RestClientConfiguration configuration) {
    checkNotNull(configuration);

    try (TcclBlock tccl = TcclBlock.begin(ResteasyClientBuilder.class)) {
      HttpContext httpContext = new BasicHttpContext();
      if (configuration.getUseTrustStore()) {
        httpContext.setAttribute(SSLContextSelector.USE_TRUST_STORE, true);
      }
      
      HttpClient client;
      if (configuration.getHttpClient() != null) {
        client = checkNotNull(configuration.getHttpClient().get());
      }
      else {
        client = httpClient.get();
      }
      
      // Configure ApacheHttpClient4Engine with Virtual Threads for asynchronous operations
      ApacheHttpClient4Engine httpEngine = new ApacheHttpClient4Engine(client, httpContext);
      httpEngine.setExecutor(virtualThreadExecutor); // Use Virtual Threads for HTTP operations
      
      if (log.isDebugEnabled()) {
        log.debug("Created ApacheHttpClient4Engine with Virtual Threads executor");
      }

      // Configure ResteasyClientBuilder with updated API for 6.2.7.Final compatibility
      ResteasyClientBuilder builder = new ResteasyClientBuilder();
      builder.httpEngine(httpEngine);
      
      // Apply custom configuration if provided
      if (configuration.getCustomizer() != null) {
        configuration.getCustomizer().apply(builder);
      }

      return builder.build();
    }
  }

  @Override
  public <T> T proxy(final Class<T> api, final Client client, final URI baseUri) {
    WebTarget target = client.target(baseUri);
    
    if (log.isDebugEnabled()) {
      log.debug("Creating proxy for {} with Virtual Threads support", api.getName());
    }
    
    // Configure proxy builder with optimized classloader handling for Virtual Threads
    return ProxyBuilder.builder(api, target)
        .classloader(bridgeClassLoaderCache.getUnchecked(api.getClassLoader()))
        .executor(virtualThreadExecutor) // Use Virtual Threads for proxy method invocations
        .build();
  }
}