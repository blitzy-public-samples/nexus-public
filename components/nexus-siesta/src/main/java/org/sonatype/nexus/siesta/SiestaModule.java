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

import jakarta.inject.Named;

import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.security.FilterChainModule;
import org.sonatype.nexus.security.SecurityFilter;
import org.sonatype.nexus.security.anonymous.AnonymousFilter;
import org.sonatype.nexus.security.authc.AntiCsrfFilter;
import org.sonatype.nexus.security.authc.NexusAuthenticationFilter;
import org.sonatype.nexus.security.VirtualThreadContextFilter;

import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.servlet.ServletModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.common.app.FeatureFlags.SESSION_ENABLED;

/**
 * Siesta plugin module.
 *
 * @since 2.4
 */
@Named
@FeatureFlag(name = SESSION_ENABLED)
public class SiestaModule
    extends AbstractModule
{
  public static final String MOUNT_POINT = "/service/rest";

  private static final Logger log = LoggerFactory.getLogger(SiestaModule.class);

  public static final String SKIP_MODULE_CONFIGURATION = SiestaModule.class.getName() + ".skip";

  /**
   * RESTEasy version compatibility constant
   */
  private static final String RESTEASY_VERSION = "6.2.7.Final";

  @Override
  protected void configure() {
    // HACK: avoid configuration of this module in cases as it is not wanted. e.g. automatically discovered by Sisu
    if (!Boolean.getBoolean(SKIP_MODULE_CONFIGURATION)) {
      doConfigure();
    }
  }

  private void doConfigure() {
    // Install RESTEasy module with Java 21 Virtual Thread support
    install(new ResteasyModule());

    // Configure servlet with Virtual Thread support
    install(configureServletModule());

    // Configure filter chain with context propagation for Virtual Threads
    install(configureFilterChainModule());
    
    log.info(STR."Configured Siesta with RESTEasy {RESTEASY_VERSION} and Java 21 Virtual Thread support");
  }

  /**
   * Configure the servlet module with Virtual Thread support for improved concurrency.
   */
  protected ServletModule configureServletModule() {
    return new ServletModule()
    {
      @Override
      protected void configureServlets() {
        log.debug(STR."Mount point: {MOUNT_POINT}");

        // Bind the SiestaServlet with Virtual Thread support
        bind(SiestaServlet.class);
        
        // Configure servlet with RESTEasy parameters and Virtual Thread support
        serve(MOUNT_POINT + "/*").with(SiestaServlet.class, ImmutableMap.of(
            "resteasy.servlet.mapping.prefix", MOUNT_POINT,
            "resteasy.async.job.service.enabled", "true",
            "resteasy.async.job.service.max.job.results", "100",
            "resteasy.async.job.service.max.wait", "300000",
            "resteasy.async.job.service.thread.pool.size", "100",
            "resteasy.async.job.service.base.path", MOUNT_POINT + "/async-jobs",
            "resteasy.virtualthread.enabled", "true"
        ));
        
        // Apply security filter with Virtual Thread context propagation
        filter(MOUNT_POINT + "/*").through(VirtualThreadContextFilter.class);
        filter(MOUNT_POINT + "/*").through(SecurityFilter.class);
      }
    };
  }

  /**
   * Configure the filter chain module with proper context propagation for Virtual Threads.
   */
  protected Module configureFilterChainModule() {
    return new FilterChainModule()
    {
      @Override
      protected void configure() {
        // Configure filter chain with Virtual Thread context propagation
        addFilterChain(MOUNT_POINT + "/**",
            VirtualThreadContextFilter.NAME,
            NexusAuthenticationFilter.NAME,
            AnonymousFilter.NAME,
            AntiCsrfFilter.NAME);
      }
    };
  }
}
