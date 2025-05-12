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
package com.sonatype.nexus.docker.testsupport.nginx;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.sonatype.nexus.docker.testsupport.ContainerCommandLineITSupport;
import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

import static java.lang.StringTemplate.STR;

/**
 * Nginx implementation of a Docker Command Line enabled container.
 * 
 * This class provides methods to control Nginx service in a Docker container,
 * leveraging Java 21 features for improved performance and readability:
 * <ul>
 *   <li>String templates for command construction</li>
 *   <li>Virtual threads for non-blocking command execution</li>
 *   <li>CompletableFuture for asynchronous operations</li>
 * </ul>
 *
 * @since 3.16
 */
public class NginxCommandLineITSupport
    extends ContainerCommandLineITSupport
{
  private static final String SERVICE_CMD = "service";
  private static final String NGINX_CMD = "nginx";
  private static final String REDIRECT_OUTPUT = "> /dev/null 2>&1";

  /**
   * Constructor.
   *
   * @param dockerContainerConfig {@link DockerContainerConfig}
   */
  public NginxCommandLineITSupport(final DockerContainerConfig dockerContainerConfig) {
    super(dockerContainerConfig);
  }

  /**
   * Runs a nginx server by running <code>service nginx start</code>
   * 
   * @return Optional containing command output if available
   */
  public Optional<List<String>> nginxServiceStart() {
    log.debug("Starting Nginx service");
    return exec(STR"\{SERVICE_CMD} \{NGINX_CMD} start \{REDIRECT_OUTPUT}");
  }

  /**
   * Stops a nginx server by running <code>service nginx stop</code>
   * 
   * @return Optional containing command output if available
   */
  public Optional<List<String>> nginxServiceStop() {
    log.debug("Stopping Nginx service");
    return exec(STR"\{SERVICE_CMD} \{NGINX_CMD} stop \{REDIRECT_OUTPUT}");
  }

  /**
   * Restarts the Nginx service by running <code>service nginx restart</code>
   * 
   * @return Optional containing command output if available
   */
  public Optional<List<String>> nginxServiceRestart() {
    log.debug("Restarting Nginx service");
    return exec(STR"\{SERVICE_CMD} \{NGINX_CMD} restart \{REDIRECT_OUTPUT}");
  }

  /**
   * Checks the status of the Nginx service by running <code>service nginx status</code>
   * 
   * @return Optional containing command output if available
   */
  public Optional<List<String>> nginxServiceStatus() {
    log.debug("Checking Nginx service status");
    return exec(STR"\{SERVICE_CMD} \{NGINX_CMD} status");
  }

  /**
   * Asynchronously starts the Nginx service using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads for non-blocking execution,
   * allowing the caller to continue processing while the service starts.
   *
   * @return CompletableFuture that will be completed with the command output when execution finishes
   */
  public CompletableFuture<Optional<List<String>>> nginxServiceStartAsync() {
    log.debug("Starting Nginx service asynchronously");
    return execAsyncWithResult(STR"\{SERVICE_CMD} \{NGINX_CMD} start \{REDIRECT_OUTPUT}");
  }

  /**
   * Asynchronously stops the Nginx service using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads for non-blocking execution,
   * allowing the caller to continue processing while the service stops.
   *
   * @return CompletableFuture that will be completed with the command output when execution finishes
   */
  public CompletableFuture<Optional<List<String>>> nginxServiceStopAsync() {
    log.debug("Stopping Nginx service asynchronously");
    return execAsyncWithResult(STR"\{SERVICE_CMD} \{NGINX_CMD} stop \{REDIRECT_OUTPUT}");
  }

  /**
   * Asynchronously restarts the Nginx service using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads for non-blocking execution,
   * allowing the caller to continue processing while the service restarts.
   *
   * @return CompletableFuture that will be completed with the command output when execution finishes
   */
  public CompletableFuture<Optional<List<String>>> nginxServiceRestartAsync() {
    log.debug("Restarting Nginx service asynchronously");
    return execAsyncWithResult(STR"\{SERVICE_CMD} \{NGINX_CMD} restart \{REDIRECT_OUTPUT}");
  }

  /**
   * Reloads the Nginx configuration without stopping the service.
   * <p>
   * This is useful for applying configuration changes without disrupting active connections.
   * 
   * @return Optional containing command output if available
   */
  public Optional<List<String>> nginxReloadConfig() {
    log.debug("Reloading Nginx configuration");
    return exec(STR"\{SERVICE_CMD} \{NGINX_CMD} reload \{REDIRECT_OUTPUT}");
  }

  /**
   * Asynchronously reloads the Nginx configuration using virtual threads.
   * <p>
   * This method leverages Java 21 virtual threads for non-blocking execution,
   * allowing the caller to continue processing while the configuration reloads.
   *
   * @return CompletableFuture that will be completed with the command output when execution finishes
   */
  public CompletableFuture<Optional<List<String>>> nginxReloadConfigAsync() {
    log.debug("Reloading Nginx configuration asynchronously");
    return execAsyncWithResult(STR"\{SERVICE_CMD} \{NGINX_CMD} reload \{REDIRECT_OUTPUT}");
  }
}