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
package com.sonatype.nexus.docker.testsupport.saml.idp;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.sonatype.nexus.docker.testsupport.ContainerCommandLineITSupport;
import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

import static java.lang.StringTemplate.STR;
import static java.util.Objects.nonNull;
import static org.sonatype.nexus.common.io.NetworkHelper.findLocalHostAddress;
import static org.testcontainers.shaded.org.awaitility.Awaitility.await;

/**
 * Support class for SAML Identity Provider (IdP) container testing.
 * <p>
 * This class provides functionality to start, configure, and interact with a SAML IdP
 * container for integration testing. It leverages Java 21 features including:
 * <ul>
 *   <li>Virtual threads for improved concurrency in server readiness polling</li>
 *   <li>Pattern matching for cleaner error handling</li>
 *   <li>String templates for more readable logging</li>
 * </ul>
 * <p>
 * The implementation uses a non-blocking approach to server readiness checking,
 * which improves test efficiency and resource utilization.
 */
public class IdpCommandLineITSupport
    extends ContainerCommandLineITSupport
{
  /**
   * The default container port for the IdP server.
   */
  public static final String IDP_CONTAINER_PORT = "8080";
  
  /**
   * Maximum time to wait for the IdP server to become available.
   */
  private static final Duration MAX_WAIT_TIME = Duration.ofMinutes(2);
  
  /**
   * Connection timeout for server availability checks.
   */
  private static final int CONNECTION_TIMEOUT_MS = 1000;
  
  /**
   * Virtual thread executor for concurrent server polling operations.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * The mapped host port for the IdP container.
   */
  private Integer idpHostPort;

  /**
   * The host address for the IdP server.
   */
  private String idpHost;

  /**
   * Constructs a new IdP command line support instance.
   *
   * @param dockerContainerConfig the Docker container configuration
   */
  public IdpCommandLineITSupport(final DockerContainerConfig dockerContainerConfig) {
    super(dockerContainerConfig, null);
  }

  /**
   * Waits for the IdP server to become available.
   * <p>
   * This method uses Java 21 virtual threads for efficient concurrent polling
   * of the server status. It will wait up to 2 minutes for the server to respond
   * successfully to HTTP requests.
   *
   * @throws Exception if an error occurs during the waiting process
   */
  public void awaitIdpServer() throws Exception {
    idpHostPort = getHostTcpPort(IDP_CONTAINER_PORT);
    idpHost = findLocalHostAddress();

    log.info(STR"Awaiting idp server \{idpHost}:\{idpHostPort}");

    // Use Awaitility with Java 21 compatibility
    await().atMost(MAX_WAIT_TIME.toMillis(), TimeUnit.MILLISECONDS)
           .pollExecutorService(virtualThreadExecutor)
           .until(this::isIdpServerAvailable);

    log.info(STR"Finished waiting for idp server \{idpHost}:\{idpHostPort}");
  }

  /**
   * Checks if the IdP server is available by attempting to connect to it.
   * <p>
   * This method uses pattern matching for cleaner exception handling and
   * resource management.
   *
   * @return true if the server is available, false otherwise
   */
  private boolean isIdpServerAvailable() {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) getIdpUrl().openConnection();
      connection.setConnectTimeout(CONNECTION_TIMEOUT_MS);
      connection.setReadTimeout(CONNECTION_TIMEOUT_MS);
      connection.getInputStream();
      return true;
    }
    catch (Exception e) { // NOSONAR
      // Using pattern matching to handle different exception types
      return switch(e) {
        case MalformedURLException mue -> {
          log.debug(STR"Malformed URL while checking IdP server: \{mue.getMessage()}");
          yield false;
        }
        case java.net.ConnectException ce -> {
          log.trace(STR"Connection refused while checking IdP server: \{ce.getMessage()}");
          yield false;
        }
        default -> {
          log.trace(STR"Error checking IdP server availability: \{e.getClass().getSimpleName()}");
          yield false;
        }
      };
    }
    finally {
      if (nonNull(connection)) {
        connection.disconnect();
      }
    }
  }

  /**
   * Gets the URL for the IdP server.
   *
   * @return the IdP server URL
   * @throws MalformedURLException if the URL is malformed
   */
  public URL getIdpUrl() throws MalformedURLException {
    return getIdpUri().toURL();
  }

  /**
   * Gets the URI for the IdP server.
   *
   * @return the IdP server URI
   */
  public URI getIdpUri() {
    return URI.create(STR"http://\{idpHost}:\{idpHostPort}").normalize();
  }
  
  /**
   * Asynchronously checks if the IdP server is available.
   * <p>
   * This method leverages Java 21 virtual threads and CompletableFuture for
   * non-blocking server availability checking.
   *
   * @return a CompletableFuture that completes with true when the server is available
   */
  public CompletableFuture<Boolean> checkIdpServerAvailableAsync() {
    return CompletableFuture.supplyAsync(this::isIdpServerAvailable, virtualThreadExecutor);
  }
  
  /**
   * Cleans up resources used by this support class.
   * <p>
   * This method should be called when the support class is no longer needed to
   * ensure proper resource cleanup.
   */
  @Override
  public void exit() {
    virtualThreadExecutor.close();
    super.exit();
  }
}