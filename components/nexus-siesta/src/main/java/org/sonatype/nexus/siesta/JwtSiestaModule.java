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
package org.sonatype.nexus.siesta;

import javax.inject.Named;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.security.FilterChainModule;
import org.sonatype.nexus.security.JwtFilter;
import org.sonatype.nexus.security.JwtSecurityFilter;
import org.sonatype.nexus.security.anonymous.AnonymousFilter;
import org.sonatype.nexus.security.authc.AntiCsrfFilter;
import org.sonatype.nexus.security.authc.NexusAuthenticationFilter;
import org.sonatype.nexus.thread.VirtualThreadFactory;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Module;
import com.google.inject.servlet.ServletModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.common.app.FeatureFlags.JWT_ENABLED;

/**
 * Siesta plugin module using {@link JwtSecurityFilter} with support for Java 21 Virtual Threads.
 * 
 * This module configures the RESTEasy 6.2.7.Final integration with JWT authentication
 * to leverage Virtual Threads for improved concurrency and performance.
 *
 * @since 3.38
 */
@Named
@FeatureFlag(name = JWT_ENABLED)
public class JwtSiestaModule
  extends SiestaModule
{
  private static final Logger log = LoggerFactory.getLogger(JwtSiestaModule.class);
  
  /**
   * Configuration parameter for enabling Virtual Threads in the servlet container.
   */
  private static final String VIRTUAL_THREADS_ENABLED = "resteasy.servlet.virtualThreads.enabled";
  
  /**
   * Configuration parameter for the RESTEasy executor service factory class.
   */
  private static final String EXECUTOR_SERVICE_FACTORY = "resteasy.servlet.async.executorServiceFactory";

  @Override
  protected ServletModule configureServletModule() {
    return new ServletModule()
    {
      @Override
      protected void configureServlets() {
        log.debug("Mount point: {}", MOUNT_POINT);

        // Bind the SiestaServlet with Virtual Thread support for RESTEasy 6.2.7.Final
        bind(SiestaServlet.class);
        serve(MOUNT_POINT + "/*").with(SiestaServlet.class, ImmutableMap.<String, String>builder()
            .put("resteasy.servlet.mapping.prefix", MOUNT_POINT)
            // Enable Virtual Threads for RESTEasy servlet processing
            .put(VIRTUAL_THREADS_ENABLED, "true")
            // Configure RESTEasy to use our VirtualThreadFactory for async operations
            .put(EXECUTOR_SERVICE_FACTORY, VirtualThreadFactory.class.getName())
            // Ensure compatibility with RESTEasy 6.2.7.Final
            .put("resteasy.preferJacksonOverJsonB", "true")
            // Increase the number of concurrent connections the servlet can handle
            .put("resteasy.servlet.async.threadPoolSize", "0") // 0 means unlimited when using Virtual Threads
            .build()
        );
        
        // Configure the JWT security filter with context propagation for Virtual Threads
        filter(MOUNT_POINT + "/*").through(JwtSecurityFilter.class, ImmutableMap.of(
            "virtualThreadsEnabled", "true"
        ));
      }
    };
  }

  @Override
  protected Module configureFilterChainModule() {
    return new FilterChainModule()
    {
      @Override
      protected void configure() {
        // Configure the filter chain with proper context propagation for Virtual Threads
        // The order of filters is important for security and performance
        addFilterChain(MOUNT_POINT + "/**",
            // Authentication filter comes first to validate credentials
            NexusAuthenticationFilter.NAME,
            // JWT filter to process token-based authentication with Virtual Thread support
            JwtFilter.NAME,
            // Anonymous access filter if no authentication is provided
            AnonymousFilter.NAME,
            // CSRF protection filter with Java 21 security enhancements
            AntiCsrfFilter.NAME);
        
        // Configure thread context propagation for Virtual Threads
        // This ensures security context is properly maintained across thread boundaries
        bindConstant().annotatedWith(named("security.threadContextInheritable")).to(true);
      }
      
      /**
       * Helper method to create named bindings for filter chain configuration.
       */
      private com.google.inject.name.Named named(String name) {
        return com.google.inject.name.Names.named(name);
      }
    };
  }
}