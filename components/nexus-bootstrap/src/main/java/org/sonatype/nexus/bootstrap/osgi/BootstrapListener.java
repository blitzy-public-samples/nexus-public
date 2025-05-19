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
package org.sonatype.nexus.bootstrap.osgi;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.FeaturesService.Option;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.karaf.features.FeaturesService.Option.NoAutoRefreshBundles;
import static org.apache.karaf.features.FeaturesService.Option.NoAutoRefreshManagedBundles;

/**
 * {@link ServletContextListener} that bootstraps an OSGi-based application.
 * Updated for Java 21 with Virtual Threads support.
 *
 * @since 3.0
 */
public class BootstrapListener
    implements ServletContextListener
{

  private static final Logger log = LoggerFactory.getLogger(BootstrapListener.class);
  
  // Executor service for handling concurrent operations with Virtual Threads
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  private ListenerTracker listenerTracker;

  private FilterTracker filterTracker;

  private final NexusEditionPropertiesConfigurer propertiesConfigurer = new NexusEditionPropertiesConfigurer();

  @Override
  public void contextInitialized(final ServletContextEvent event) {
    log.info("Initializing");
    ServletContext servletContext = event.getServletContext();
    try {
      Properties properties = propertiesConfigurer.getPropertiesFromConfiguration();
      // pass bootstrap properties to embedded servlet listener
      servletContext.setAttribute("nexus.properties", properties);
      // are we already running in OSGi or should we embed OSGi?
      Bundle containingBundle = FrameworkUtil.getBundle(getClass());
      BundleContext bundleContext;
      if (containingBundle != null) {
        bundleContext = containingBundle.getBundleContext();
      }
      else {
        // when we support running in embedded mode this is where it'll go
        throw new UnsupportedOperationException("Missing OSGi container");
      }
      // bootstrap our chosen Nexus edition
      installNexusEdition(bundleContext, properties);

      // watch out for the real Nexus listener
      listenerTracker = new ListenerTracker(bundleContext, "nexus", servletContext);
      listenerTracker.open();

      // watch out for the real Nexus filter
      filterTracker = new FilterTracker(bundleContext, "nexus");
      filterTracker.open();

      // Use CompletableFuture to wait for services concurrently
      CompletableFuture<Void> listenerFuture = CompletableFuture.runAsync(
          () -> listenerTracker.waitForService(0), virtualThreadExecutor);
      CompletableFuture<Void> filterFuture = CompletableFuture.runAsync(
          () -> filterTracker.waitForService(0), virtualThreadExecutor);
      
      // Wait for both services to be available
      CompletableFuture.allOf(listenerFuture, filterFuture).join();
    }
    catch (Exception e) {
      log.error("Failed to initialize", e);
      throw e instanceof RuntimeException ? ((RuntimeException) e) : new RuntimeException(e);
    }

    log.info("Initialized");
  }

  /**
   * Installs the Nexus edition feature and database feature based on properties.
   * Updated for compatibility with Karaf 4.4.4 feature API.
   */
  private static void installNexusEdition(final BundleContext ctx, final Properties properties) throws Exception {
    String editionName = properties.getProperty(NexusEditionPropertiesConfigurer.NEXUS_EDITION);
    if (editionName != null && !editionName.isEmpty()) {
      // Use try-with-resources to ensure tracker is closed properly
      try (ServiceTracker<FeaturesService, FeaturesService> tracker = 
          new ServiceTracker<>(ctx, FeaturesService.class, null)) {
        tracker.open();
        
        // Use Virtual Thread to wait for service asynchronously
        CompletableFuture<FeaturesService> featureServiceFuture = CompletableFuture.supplyAsync(() -> {
          try {
            return tracker.waitForService(1000);
          } 
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for FeaturesService", e);
          }
        }, Thread.ofVirtual().factory());
        
        FeaturesService featuresService = featureServiceFuture.join();
        if (featuresService == null) {
          throw new IllegalStateException("FeaturesService not available");
        }
        
        Feature editionFeature = featuresService.getFeature(editionName);
        checkNotNull(editionFeature, "Unable to find feature " + editionName);
        properties.put(NexusEditionPropertiesConfigurer.NEXUS_FULL_EDITION, editionFeature.toString());

        String dbFeatureName = properties.getProperty(NexusEditionPropertiesConfigurer.NEXUS_DB_FEATURE);
        Feature dbFeature = featuresService.getFeature(dbFeatureName);
        checkNotNull(dbFeature, "Unable to find feature " + dbFeatureName);

        log.info("Installing: {} ({})", editionFeature, dbFeature);

        Set<String> featureIds = new LinkedHashSet<>();
        if (!featuresService.isInstalled(editionFeature)) {
          featureIds.add(editionFeature.getId());
        }
        if (!featuresService.isInstalled(dbFeature)) {
          featureIds.add(dbFeature.getId());
        }

        // edition might already be installed in the cache; if so then skip installation
        if (!featureIds.isEmpty()) {
          // avoid auto-refreshing bundles as that could trigger unwanted restart/lifecycle events
          EnumSet<Option> options = EnumSet.of(NoAutoRefreshBundles, NoAutoRefreshManagedBundles);
          
          // Use the updated installFeatures method with proper error handling for Java 21
          try {
            featuresService.installFeatures(featureIds, options);
          } 
          catch (Exception e) {
            log.error("Failed to install features: {}", featureIds, e);
            throw new RuntimeException("Feature installation failed", e);
          }
        }

        log.info("Installed: {} ({})", editionFeature, dbFeature);
      }
    }
  }

  @Override
  public void contextDestroyed(final ServletContextEvent event) {
    log.info("Destroying");

    // Shutdown the virtual thread executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }

    // Use try-catch blocks for better error handling during shutdown
    try {
      if (filterTracker != null) {
        filterTracker.close();
        filterTracker = null;
      }
    } 
    catch (Exception e) {
      log.warn("Error closing filter tracker", e);
    }

    try {
      if (listenerTracker != null) {
        listenerTracker.close();
        listenerTracker = null;
      }
    } 
    catch (Exception e) {
      log.warn("Error closing listener tracker", e);
    }

    log.info("Destroyed");
  }
}