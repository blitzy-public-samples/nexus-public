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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.servlet.Filter;

import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Watches for a {@link Filter} with the given name and registers it with {@link DelegatingFilter}.
 * Enhanced with Java 21 Virtual Threads for improved concurrency and service handling.
 * 
 * @since 3.0
 */
public final class FilterTracker
    extends ServiceTracker<Filter, Filter>
{
  private static final String QUERY = "(&(objectClass=" + Filter.class.getName() + ")(name=%s))";
  private static final Logger LOGGER = Logger.getLogger(FilterTracker.class.getName());
  
  // Using Virtual Threads executor for non-blocking I/O operations
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  
  // Thread-safe reference to the currently tracked filter
  private final AtomicReference<Filter> currentFilter = new AtomicReference<>();

  /**
   * Creates a new tracker for Filter services with the given name.
   *
   * @param bundleContext the bundle context
   * @param name the filter name to track
   * @throws InvalidSyntaxException if the filter syntax is invalid
   */
  public FilterTracker(BundleContext bundleContext, String name) throws InvalidSyntaxException {
    super(bundleContext, FrameworkUtil.createFilter(String.format(QUERY, name)), null);
    LOGGER.fine(() -> "Created FilterTracker for filter name: " + name);
  }

  @Override
  public Filter addingService(ServiceReference<Filter> reference) {
    // Get the service using the parent implementation
    Filter service = super.addingService(reference);
    
    if (service != null) {
      // Store reference to current filter for potential error recovery
      currentFilter.set(service);
      
      // Use Virtual Thread for non-blocking service registration
      virtualThreadExecutor.execute(() -> {
        try {
          LOGGER.fine(() -> "Registering filter service: " + service.getClass().getName());
          DelegatingFilter.set(service);
        } catch (Exception e) {
          LOGGER.log(Level.SEVERE, "Error registering filter service", e);
        }
      });
    }
    
    return service;
  }

  @Override
  public void removedService(ServiceReference<Filter> reference, Filter service) {
    if (service != null) {
      // Use Virtual Thread for non-blocking service unregistration
      virtualThreadExecutor.execute(() -> {
        try {
          LOGGER.fine(() -> "Unregistering filter service: " + service.getClass().getName());
          DelegatingFilter.unset(service);
          
          // Clear the current filter reference if it matches the removed service
          currentFilter.compareAndSet(service, null);
        } catch (Exception e) {
          LOGGER.log(Level.SEVERE, "Error unregistering filter service", e);
        }
      });
    }
    
    // Call parent implementation after scheduling the unregistration
    super.removedService(reference, service);
  }
  
  @Override
  public void close() {
    // Ensure any remaining filter is properly unregistered before closing
    Filter remainingFilter = currentFilter.getAndSet(null);
    if (remainingFilter != null) {
      try {
        LOGGER.fine(() -> "Unregistering remaining filter during tracker close");
        DelegatingFilter.unset(remainingFilter);
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "Error unregistering filter during tracker close", e);
      }
    }
    
    super.close();
  }
}