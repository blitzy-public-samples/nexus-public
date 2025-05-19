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

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;

/**
 * Watches for a {@link ServletContextListener} with the given name and applies {@link ServletContext} events.
 * Leverages Java 21 Virtual Threads for improved concurrency and performance when dispatching
 * ServletContext events to registered listeners.
 * 
 * @since 3.0
 */
public final class ListenerTracker
    extends ServiceTracker<ServletContextListener, ServletContextListener>
{
  private static final String QUERY = "(&(objectClass=" + ServletContextListener.class.getName() + ")(name=%s))";
  private static final Logger LOGGER = Logger.getLogger(ListenerTracker.class.getName());

  private final ServletContext servletContext;
  private final ExecutorService virtualThreadExecutor;

  /**
   * Creates a new tracker for ServletContextListener services with the given name.
   *
   * @param bundleContext the bundle context used to track the services
   * @param name the name of the ServletContextListener to track
   * @param servletContext the servlet context to pass to the listener
   * @throws InvalidSyntaxException if the tracking filter is invalid
   */
  public ListenerTracker(BundleContext bundleContext, String name, ServletContext servletContext)
      throws InvalidSyntaxException
  {
    super(bundleContext, FrameworkUtil.createFilter(String.format(QUERY, name)), null);
    this.servletContext = servletContext;
    // Create a virtual thread executor for handling ServletContext events
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    LOGGER.info("Created ListenerTracker for ServletContextListener with name: " + name);
  }
  
  /**
   * Called when a service is being added to the tracker.
   * Dispatches the contextInitialized event to the service using a virtual thread
   * for improved concurrency and performance.
   *
   * @param reference the reference to the service being added
   * @return the service object
   */
  @Override
  public ServletContextListener addingService(ServiceReference<ServletContextListener> reference) {
    ServletContextListener service = super.addingService(reference);
    if (service != null) {
      try {
        // Use virtual thread to dispatch the contextInitialized event
        Future<?> future = virtualThreadExecutor.submit(() -> {
          try {
            LOGGER.fine("Dispatching contextInitialized event to service: " + reference);
            service.contextInitialized(new ServletContextEvent(servletContext));
            LOGGER.fine("Successfully dispatched contextInitialized event to service: " + reference);
          } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error dispatching contextInitialized event to service: " + reference, e);
            throw e; // Re-throw to be caught by the outer try-catch
          }
        });
        
        // Wait for the event to be processed
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.log(Level.WARNING, "Interrupted while dispatching contextInitialized event to service: " + reference, e);
      } catch (ExecutionException e) {
        LOGGER.log(Level.SEVERE, "Error during contextInitialized event dispatch to service: " + reference, e.getCause());
      }
    }
    return service;
  }
  
  /**
   * Called when a service is being removed from the tracker.
   * Dispatches the contextDestroyed event to the service using a virtual thread
   * for improved concurrency and performance.
   *
   * @param reference the reference to the service being removed
   * @param service the service object
   */
  @Override
  public void removedService(ServiceReference<ServletContextListener> reference, ServletContextListener service) {
    if (service != null) {
      try {
        // Use virtual thread to dispatch the contextDestroyed event
        Future<?> future = virtualThreadExecutor.submit(() -> {
          try {
            LOGGER.fine("Dispatching contextDestroyed event to service: " + reference);
            service.contextDestroyed(new ServletContextEvent(servletContext));
            LOGGER.fine("Successfully dispatched contextDestroyed event to service: " + reference);
          } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error dispatching contextDestroyed event to service: " + reference, e);
            throw e; // Re-throw to be caught by the outer try-catch
          }
        });
        
        // Wait for the event to be processed
        future.get();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOGGER.log(Level.WARNING, "Interrupted while dispatching contextDestroyed event to service: " + reference, e);
      } catch (ExecutionException e) {
        LOGGER.log(Level.SEVERE, "Error during contextDestroyed event dispatch to service: " + reference, e.getCause());
      } finally {
        super.removedService(reference, service);
      }
    } else {
      super.removedService(reference, service);
    }
  }
  
  /**
   * Closes this tracker, shutting down the virtual thread executor.
   */
  @Override
  public void close() {
    try {
      // Shutdown the virtual thread executor
      if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
        LOGGER.fine("Shutting down virtual thread executor");
        virtualThreadExecutor.shutdown();
      }
    } finally {
      super.close();
    }
  }
}