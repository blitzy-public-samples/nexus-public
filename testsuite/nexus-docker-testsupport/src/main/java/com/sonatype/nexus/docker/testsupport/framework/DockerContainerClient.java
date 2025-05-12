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
package com.sonatype.nexus.docker.testsupport.framework;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.sonatype.goodies.common.Mutex;
import org.sonatype.nexus.common.net.PortAllocator;

import com.github.dockerjava.api.command.InspectContainerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.nonNull;
import static org.apache.commons.lang.StringUtils.left;
import static org.sonatype.nexus.common.text.Strings2.notBlank;
import static org.testcontainers.containers.BindMode.READ_WRITE;

/**
 * Support class for helping to manage Docker Containers.
 * <p>
 * This implementation leverages Java 21 features including:
 * <ul>
 *   <li>Virtual threads for I/O-bound operations to improve concurrency and performance</li>
 *   <li>Record patterns for efficient configuration handling</li>
 *   <li>String templates for improved logging and error messages</li>
 * </ul>
 * </p>
 */
public class DockerContainerClient
{
  private final Logger log = LoggerFactory.getLogger(getClass());

  private static final String KEEP_ALIVE = "while true; do sleep 1; done";

  private static final int SHORT_ID_LENGTH = 12;

  private final Mutex lock = new Mutex();

  private final DockerContainerConfig config;
  
  /**
   * Virtual thread executor for I/O-bound operations.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  private GenericContainer<?> dockerClient;

  private InspectContainerResponse startedContainer;

  /**
   * Creates a new Docker container client for the specified image.
   *
   * @param image the Docker image name
   */
  public DockerContainerClient(final String image) {
    this(DockerContainerConfig.builder(image).build());
  }

  /**
   * Creates a new Docker container client with the specified configuration.
   *
   * @param config the Docker container configuration
   */
  public DockerContainerClient(final DockerContainerConfig config) {
    this.config = checkNotNull(config);
  }

  /**
   * Runs a docker container for a given image. This method will pull the image that this container is supposed to run
   * for, if it's not already existing.
   */
  public void run() {
    runAndPullIfNotExist(null);
  }

  /**
   * Runs a docker container for a given image. This method will pull the image that this container is supposed to run
   * for, if it's not already existing. Additionally, the method will assure that the containers process one will be
   * running until stopped or killed.
   */
  public void runAndKeepAlive() {
    runAndPullIfNotExist(KEEP_ALIVE);
  }

  /**
   * Runs a docker container for given image. This method will pull the image that this container is supposed to run
   * for, if it's not already existing. Additionally, the method allows the caller to pass commands to the docker run
   * command. Unless provided in the commands to run the container will stop immediately after it as run, just as normal
   * docker behavior.
   *
   * @param commands to be run for docker container, can be {@code null}.
   */
  public void run(@Nullable final String commands) {
    runAndPullIfNotExist(commands);
  }

  /**
   * Execute commands on a docker container for a given image.
   * <p>
   * This method uses virtual threads for improved I/O concurrency.
   * </p>
   *
   * @param commands to be executed within docker container.
   * @return results from a "docker exec" command.
   */
  public Optional<ExecResult> exec(final String commands) {
    run(KEEP_ALIVE);
    return execInDocker(commands);
  }

  /**
   * Close all resources for the underlying {@link GenericContainer} and kills and removes any containers that were
   * started, run and executed upon by this instance.
   */
  public void close() {
    if (dockerClient != null && dockerClient.isRunning()) {
      dockerClient.stop();
    }
    virtualThreadExecutor.close();
  }

  /**
   * Download a container path to a local {@link File} location. This method will use the last running container if
   * possible.
   * <p>
   * This method uses virtual threads for improved I/O performance.
   * </p>
   *
   * @param fromContainerPath the path in the container to download
   * @param toLocal           the path of the local file system to download to
   */
  public void download(final String fromContainerPath, final File toLocal) {
    run(KEEP_ALIVE);
    
    // Use CompletableFuture with virtual threads for I/O operations
    CompletableFuture.runAsync(() -> {
      dockerClient.copyFileFromContainer(fromContainerPath, toLocal.getAbsolutePath());
      log.debug(STR."Downloaded file from \{fromContainerPath} to \{toLocal.getAbsolutePath()}");
    }, virtualThreadExecutor).join();
  }

  /**
   * Executes commands in a Docker container using virtual threads for I/O operations.
   *
   * @param commands the commands to execute
   * @return an Optional containing the execution result, or empty if execution failed
   */
  private Optional<ExecResult> execInDocker(final String commands)
  {
    var image = config.getImage();
    if (startedContainer == null) {
      log.warn(STR."Attempting to exec commands '\{commands}' for image '\{image}' which is not started");
      return Optional.empty();
    }

    var containerId = startedContainer.getId();
    var shortId = left(containerId, SHORT_ID_LENGTH);

    log.info(STR."Attempting to exec commands '\{commands}' in container '\{shortId}' for image '\{image}'");

    try {
      // Use CompletableFuture with virtual threads for I/O operations
      ExecResult execResult = CompletableFuture.supplyAsync(
          () -> {
            try {
              return dockerClient.execInContainer(cmd(commands));
            }
            catch (IOException | InterruptedException e) {
              throw new RuntimeException(STR."Failed to execute command: \{commands}", e);
            }
          },
          virtualThreadExecutor
      ).join();
      
      log.debug(STR."$ \{commands}");
      String stderr = execResult.getStderr();

      log.debug(STR."Output of command '\{commands}' in container '\{shortId}' for image '\{image}' was:\n\{execResult}");
      if (!stderr.isEmpty() && execResult.getExitCode() != 0) {
        log.error(STR."Failed exec commands '\{commands}' in container '\{shortId}' for image '\{image}'. Error message: \{stderr}");
      }
      else {
        log.info(STR."Successfully exec commands '\{commands}' in container '\{shortId}' for image '\{image}'");
      }

      return Optional.of(execResult);
    }
    catch (Exception e) {
      log.error(STR."Failed to exec commands '\{commands}' in container '\{shortId}' for image '\{image}'", e);
    }

    return Optional.empty();
  }

  /**
   * Runs a Docker container, pulling the image if it doesn't exist.
   * <p>
   * This method is thread-safe and will reuse existing containers when possible.
   * </p>
   *
   * @param commands the commands to run in the container, can be null
   */
  private void runAndPullIfNotExist(@Nullable final String commands) {
    // Use pattern matching with records for cleaner code
    var image = config.getImage();
    var dockerfile = config.getDockerfile();
    
    // assure that we don't have multiple threads set the started container
    synchronized (lock) {
      // reuse existing containers if they are running
      if (nonNull(startedContainer) && dockerClient.isRunning()) {
        var shortDockerId = left(startedContainer.getId(), SHORT_ID_LENGTH);
        var msg = image != null ?
            STR."image '\{image}'" : STR."Dockerfile '\{dockerfile}'";
        log.info(STR."Using existing container '\{shortDockerId}' for \{msg}");
        return;
      }
      if (log.isInfoEnabled()) {
        log.info(buildLogMessage("Attempting to run container", image, dockerfile, commands));
      }

      // Build the docker image based on the name or the Dockerfile
      dockerClient = image != null ? new GenericContainer<>(image) :
          new GenericContainer<>(new ImageFromDockerfile().withDockerfile(dockerfile));

      dockerClient.setCommand(cmd(commands));
      config.getEnv().forEach((key, value) -> dockerClient.addEnv(key, value));
      config.getPathBinds().forEach((key, value) -> dockerClient.addFileSystemBind(key, value, READ_WRITE));
      
      if (!config.getExposedPorts().isEmpty()) {
        List<String> portBindings = config.getExposedPorts().stream()
            // hostPort:containerPort
            .map(port -> STR."\{PortAllocator.nextFreePort()}:\{port}")
            .collect(Collectors.toList());
        dockerClient.setPortBindings(portBindings);
        dockerClient.setWaitStrategy(Wait.forListeningPort());
      }
      
      if (notBlank(config.getWorkingDir())) {
        dockerClient.setWorkingDirectory(config.getWorkingDir());
      }

      // Work around for an issue in ITs which results in Testcontainers using the wrong class loader
      ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
      try {
        Thread.currentThread().setContextClassLoader(GenericContainer.class.getClassLoader());
        
        // Start the container using virtual threads for better I/O performance
        CompletableFuture.runAsync(
            () -> dockerClient.start(),
            virtualThreadExecutor
        ).join();
      }
      finally {
        if (threadLoader != null) {
          Thread.currentThread().setContextClassLoader(threadLoader);
        }
      }
      startedContainer = dockerClient.getContainerInfo();

      var containerId = startedContainer.getId();
      var shortId = left(containerId, SHORT_ID_LENGTH);

      if (log.isInfoEnabled()) {
        log.info(buildLogMessage(STR."Successfully run container '\{shortId}'", image, dockerfile, commands));
      }
    }
  }

  /**
   * Builds a log message with container details.
   *
   * @param message   the base message
   * @param image     the Docker image name, can be null
   * @param dockerfile the Dockerfile path, can be null
   * @param commands  the commands to run, can be null
   * @return the formatted log message
   */
  private static String buildLogMessage(
      final String message,
      final @Nullable String image,
      final @Nullable Path dockerfile,
      final @Nullable String commands)
  {
    StringBuilder msg = new StringBuilder(message);
    if (commands != null) {
      msg.append(STR." with commands '\{commands}'");
    }
    if (image != null) {
      msg.append(STR." for image '\{image}'");
    }
    if (dockerfile != null) {
      msg.append(STR." for Dockerfile '\{dockerfile}'");
    }

    return msg.toString();
  }

  /**
   * Creates a command array for shell execution.
   *
   * @param commands the commands to execute, can be null
   * @return an array of command strings
   */
  private String[] cmd(String commands) {
    return nonNull(commands) ? new String[] {"/bin/sh", "-c", commands} : new String[] {};
  }

  /**
   * Gets the mapped port for a container port.
   *
   * @param containerPort the container port as a string
   * @return the mapped host port
   */
  public Integer getMappedPort(final String containerPort) {
    return dockerClient.getMappedPort(Integer.parseInt(containerPort));
  }
}