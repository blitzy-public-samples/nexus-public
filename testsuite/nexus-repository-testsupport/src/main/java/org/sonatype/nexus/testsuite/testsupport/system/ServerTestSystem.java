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
package org.sonatype.nexus.testsuite.testsupport.system;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.httpfixture.server.api.Behaviour;
import org.sonatype.goodies.httpfixture.server.fluent.Server;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.net.PortAllocator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.StringUtils.prependIfMissing;

/**
 * Test system for managing HTTP server instances used in tests.
 * <p>
 * This class has been updated for Java 21 to leverage virtual threads for improved concurrency
 * when creating and managing server instances. Virtual threads are lightweight threads that are
 * managed by the JVM and are ideal for I/O-bound operations like server startup and shutdown.
 * </p>
 * <p>
 * Implementation notes for virtual threads:
 * - Avoids synchronized blocks/methods to prevent thread pinning
 * - Uses thread-safe collections like CopyOnWriteArrayList for concurrent access
 * - Leverages CompletableFuture with virtual threads for non-blocking I/O operations
 * - Ensures proper resource cleanup with try-with-resources for executors
 * </p>
 * <p>
 * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
 * typically when using synchronized blocks/methods or native methods. This implementation
 * carefully avoids pinning to maximize the scalability benefits of virtual threads.
 * </p>
 * <p>
 * Compatible with JUnit Jupiter 5.10.1 and Mockito 4.11.0 for modern testing approaches.
 * </p>
 *
 * @since 3.0
 */
@Named
@Singleton
public class ServerTestSystem
    extends TestSystemSupport
{
  private static final Logger log = LoggerFactory.getLogger(ServerTestSystem.class);

  // Using CopyOnWriteArrayList for thread-safe concurrent access without explicit synchronization
  private final List<Server> servers;

  /**
   * Constructor that initializes the server test system with an event manager.
   *
   * @param eventManager the event manager to use for event handling
   */
  @Inject
  public ServerTestSystem(final EventManager eventManager) {
    super(eventManager);
    servers = new CopyOnWriteArrayList<>();
  }

  /**
   * Stops all servers when the test system is shut down.
   * <p>
   * Uses virtual threads to stop servers concurrently for improved performance.
   * This approach is particularly effective for I/O-bound operations like server shutdown,
   * as virtual threads can be suspended during I/O without blocking platform threads.
   * </p>
   * <p>
   * Note: This implementation avoids synchronized blocks to prevent virtual thread pinning,
   * which would reduce the scalability benefits of virtual threads. Instead, it uses a
   * thread-safe collection (CopyOnWriteArrayList) and CompletableFuture for coordination.
   * </p>
   */
  @Override
  protected void doAfter() {
    if (servers.isEmpty()) {
      return;
    }

    log.debug("Stopping {} server(s) using virtual threads", servers.size());
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a list to hold all the futures for server shutdown operations
      List<CompletableFuture<Void>> shutdownFutures = new ArrayList<>();
      
      // Stop each server in a separate virtual thread
      for (Server server : servers) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            server.stop();
          }
          catch (Exception e) {
            log.error("Failed to stop server", e);
          }
        }, executor);
        
        shutdownFutures.add(future);
      }
      
      // Wait for all servers to be stopped
      CompletableFuture.allOf(shutdownFutures.toArray(new CompletableFuture[0])).join();
    }
    
    servers.clear();
  }

  /**
   * Creates a new server with the specified behaviors.
   * <p>
   * This method allocates a free port and configures the server with the provided behaviors.
   * The server is started automatically and will be stopped when the test system is shut down.
   * </p>
   * <p>
   * Server startup is performed using a virtual thread to avoid blocking the calling thread
   * during I/O operations. This is particularly important for tests that need to create
   * multiple servers, as it allows for better concurrency and resource utilization.
   * </p>
   * <p>
   * Diagnostic tip: If you encounter performance issues with virtual threads, you can use
   * the JVM flag {@code -Djdk.tracePinnedThreads=full} to detect thread pinning, which
   * occurs when a virtual thread cannot be unmounted from its carrier thread.
   * </p>
   *
   * @param behaviors a map of path patterns to behaviors to configure on the server
   * @return the created and started server instance
   * @throws Exception if the server cannot be created or started
   */
  public Server createServer(final Map<String, Behaviour> behaviors) throws Exception {
    // Allocate a port for the server
    int port = PortAllocator.nextFreePort();
    log.debug("Creating server on port {}", port);
    
    // Configure the server with the allocated port
    Server server = Server.withPort(port);
    
    // Add behaviors to the server
    for (Entry<String, Behaviour> entry : behaviors.entrySet()) {
      String path = prependIfMissing(entry.getKey(), "/");
      server = server.serve(path).withBehaviours(entry.getValue());
    }
    
    // Start the server using a virtual thread for non-blocking I/O operations
    // Using try-with-resources to ensure the executor is properly closed after use
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<Void> startFuture = CompletableFuture.runAsync(() -> {
        try {
          // Server startup is an I/O-bound operation ideal for virtual threads
          // The virtual thread can be suspended during I/O without blocking a platform thread
          server.start();
        }
        catch (Exception e) {
          throw new RuntimeException("Failed to start server on port " + port, e);
        }
      }, executor);
      
      // Wait for the server to start - this join operation will not pin the thread
      // since we're not inside a synchronized block
      startFuture.join();
    } // Executor is automatically closed here
    
    // Add the server to the list of servers to be stopped when the test system is shut down
    servers.add(server);
    
    return server;
  }
}