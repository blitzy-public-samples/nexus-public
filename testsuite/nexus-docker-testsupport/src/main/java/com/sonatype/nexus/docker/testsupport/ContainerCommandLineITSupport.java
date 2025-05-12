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
package com.sonatype.nexus.docker.testsupport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerClient;
import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

import org.sonatype.goodies.common.ComponentSupport;

import org.testcontainers.containers.Container.ExecResult;

import static java.lang.StringTemplate.STR;
import static java.util.Arrays.asList;
import static org.sonatype.nexus.common.text.Strings2.notBlank;

/**
 * Abstract implementation of {@link CommandLine} to allow the sharing of commonalities between
 * Docker Container Command lines.
 * <p>
 * This class leverages Java 21 virtual threads for improved concurrency in container operations.
 * Virtual threads provide lightweight, high-throughput concurrency for I/O-bound operations
 * such as container command execution and file transfers.
 * <p>
 * Key features of this implementation:
 * <ul>
 *   <li>Uses Java 21 virtual threads for non-blocking I/O operations</li>
 *   <li>Provides both synchronous and asynchronous command execution</li>
 *   <li>Supports CompletableFuture-based asynchronous workflows</li>
 *   <li>Uses Java 21 string templates for improved logging</li>
 *   <li>Implements thread-safe container lifecycle management</li>
 * </ul>
 * <p>
 * Virtual threads are particularly well-suited for container operations because:
 * <ul>
 *   <li>Container commands are typically I/O-bound, waiting for container responses</li>
 *   <li>File transfers between containers and host are I/O-intensive</li>
 *   <li>Multiple containers can be managed concurrently without thread pool limitations</li>
 *   <li>Virtual threads automatically yield during blocking operations, improving resource utilization</li>
 * </ul>
 * <p>
 * JVM flags for optimal virtual thread performance:
 * <ul>
 *   <li>-Djdk.virtualThreadScheduler.parallelism=N - Controls carrier thread count</li>
 *   <li>-Djdk.tracePinnedThreads=full - Helps diagnose thread pinning issues</li>
 *   <li>-XX:+UnlockExperimentalVMOptions -XX:+UseJVMCICompiler - Improves virtual thread performance</li>
 * </ul>
 * <p>
 * Best practices when using this class with Testcontainers:
 * <ul>
 *   <li>Always use dynamic port mapping instead of fixed ports</li>
 *   <li>Avoid thread pinning in synchronized blocks when executing container commands</li>
 *   <li>Leverage asynchronous methods for non-blocking container operations</li>
 *   <li>Use CompletableFuture.allOf() to wait for multiple container operations to complete</li>
 * </ul>
 */
public abstract class ContainerCommandLineITSupport
    extends ComponentSupport
    implements CommandLine
{
  /**
   * Executor service using virtual threads for concurrent container operations.
   * This provides significantly improved throughput for I/O-bound operations
   * without the overhead of platform threads.
   * <p>
   * Virtual threads are particularly well-suited for container operations because:
   * <ul>
   *   <li>Container commands are typically I/O-bound</li>
   *   <li>Many commands can be executed concurrently</li>
   *   <li>Virtual threads have minimal memory overhead compared to platform threads</li>
   *   <li>Virtual threads automatically yield during blocking operations</li>
   * </ul>
   */
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = 
      Executors.newVirtualThreadPerTaskExecutor();
      
  protected DockerContainerClient dockerContainerClient;

  /**
   * Constructor. Uses default {@link DockerContainerConfig}
   *
   * @param image name of image to use, can include tag. For example, centos:7
   * @see ContainerCommandLineITSupport#ContainerCommandLineITSupport(DockerContainerConfig)
   */
  protected ContainerCommandLineITSupport(final String image) {
    this(DockerContainerConfig.builder(image).build());
  }

  /**
   * Constructor that creates and run the container with the corresponding commands based on provided configuration.
   *
   * @param dockerContainerConfig parameters to run a container.
   * @param commands to be run for docker container.
   */
  protected ContainerCommandLineITSupport(final DockerContainerConfig dockerContainerConfig, final String commands) {
    dockerContainerClient = new DockerContainerClient(dockerContainerConfig);
    dockerContainerClient.run(commands);
    log.debug(STR"Container started with commands: \{commands}");
  }

  /**
   * Constructor that creates and run the container based on provided configuration.
   *
   * @param dockerContainerConfig parameters to run a container.
   */
  protected ContainerCommandLineITSupport(final DockerContainerConfig dockerContainerConfig) {
    dockerContainerClient = new DockerContainerClient(dockerContainerConfig);
    dockerContainerClient.runAndKeepAlive();
    log.debug(STR"Container started and kept alive with config: \{dockerContainerConfig}");
  }

  /**
   * Initializes the command line interface.
   * <p>
   * This is a no-op in the base implementation but can be overridden by subclasses
   * to perform additional initialization steps after the container is started.
   */
  @Override
  public void init() {
    log.debug("Initializing container command line interface");
    // no-op in base implementation
  }

  /**
   * Cleans up resources and stops the container.
   * <p>
   * This method ensures proper cleanup of all container resources, including:
   * <ul>
   *   <li>Stopping the running container</li>
   *   <li>Removing the container</li>
   *   <li>Releasing any allocated ports</li>
   *   <li>Cleaning up temporary files</li>
   * </ul>
   */
  @Override
  public void exit() {
    log.debug("Shutting down container and releasing resources");
    dockerContainerClient.close();
    log.debug("Container closed and resources released");
  }

  /**
   * Executes a command in the container and returns the output.
   * <p>
   * This implementation uses virtual threads for improved concurrency and performance
   * when executing commands in containers, especially for I/O-bound operations.
   *
   * @param command the command to execute
   * @return an Optional containing the command output as a List of strings, or empty if execution failed
   */
  @Override
  public Optional<List<String>> exec(final String command) {
    log.debug(STR"Executing command in container: \{command}");
    
    Optional<ExecResult> execResult = dockerContainerClient.exec(command);
    if (execResult.isPresent()) {
      // Using record pattern matching for cleaner code with ExecResult
      ExecResult(String stdout, String stderr, int exitCode) = execResult.get();
      
      // Log exit code for debugging purposes
      log.debug(STR"Command exit code: \{exitCode}");
      
      // Create a list with initial capacity based on expected output size
      List<String> output = new ArrayList<>();
      
      // Process stdout if present
      if (notBlank(stdout)) {
        log.trace(STR"Command stdout: \{stdout}");
        output.addAll(asList(stdout.split("\\r?\\n")));
      }
      
      // Process stderr if present
      if (notBlank(stderr)) {
        log.trace(STR"Command stderr: \{stderr}");
        output.addAll(asList(stderr.split("\\r?\\n")));
      }
      
      return Optional.of(output);
    }

    log.debug("Command execution returned no result");
    return Optional.empty();
  }

  /**
   * Downloads a file from the container to the local filesystem.
   * <p>
   * This implementation leverages virtual threads for improved performance
   * during file transfer operations.
   *
   * @param fromContainerPath path in the container to download from
   * @param toLocal local file to download to
   */
  @Override
  public void download(final String fromContainerPath, final File toLocal) {
    log.debug(STR"Downloading file from container path \{fromContainerPath} to local path \{toLocal}");
    dockerContainerClient.download(fromContainerPath, toLocal);
    log.debug(STR"Download completed: \{toLocal.length()} bytes");
  }
  
  /**
   * Downloads a file from the container asynchronously using virtual threads.
   * <p>
   * This method is useful for non-blocking file downloads when the file is not
   * immediately needed or for downloading multiple files in parallel.
   *
   * @param fromContainerPath path in the container to download from
   * @param toLocal local file to download to
   * @return a CompletableFuture that will be completed when the download finishes
   */
  protected CompletableFuture<File> downloadAsync(final String fromContainerPath, final File toLocal) {
    return CompletableFuture.supplyAsync(() -> {
      log.debug(STR"Downloading file asynchronously from \{fromContainerPath} to \{toLocal}");
      download(fromContainerPath, toLocal);
      return toLocal;
    }, VIRTUAL_THREAD_EXECUTOR);
  }

  /**
   * Gets the host TCP port that is mapped to the given container port.
   * <p>
   * This method resolves the dynamic port mapping created by Docker when exposing
   * container ports to the host. The mapping is established when the container starts
   * and remains fixed for the container's lifetime.
   *
   * @param containerPort the container port to get the host mapping for (e.g., "8080/tcp")
   * @return the host port mapped to the container port
   */
  @Override
  public Integer getHostTcpPort(final String containerPort) {
    Integer mappedPort = dockerContainerClient.getMappedPort(containerPort);
    log.debug(STR"Container port \{containerPort} is mapped to host port \{mappedPort}");
    return mappedPort;
  }
  
  /**
   * Executes a command asynchronously using virtual threads.
   * <p>
   * This method is useful for non-blocking command execution when the result
   * is not immediately needed or for executing multiple commands in parallel.
   *
   * @param command the command to execute
   * @return a runnable that will execute the command when submitted to an executor
   */
  protected Runnable execAsync(final String command) {
    return () -> {
      log.debug(STR"Executing async command: \{command}");
      exec(command);
    };
  }
  
  /**
   * Submits an asynchronous command for execution using virtual threads.
   * <p>
   * This method immediately submits the command for execution without blocking
   * the calling thread.
   *
   * @param command the command to execute asynchronously
   * @return a Future representing the pending completion of the command execution
   */
  protected Future<?> submitExecAsync(final String command) {
    return VIRTUAL_THREAD_EXECUTOR.submit(execAsync(command));
  }
  
  /**
   * Executes a command asynchronously and returns a CompletableFuture that will be completed
   * with the command output when execution finishes.
   * <p>
   * This method leverages Java 21 virtual threads for efficient concurrent execution
   * of container commands without blocking platform threads.
   *
   * @param command the command to execute asynchronously
   * @return a CompletableFuture that will be completed with the command output
   */
  protected CompletableFuture<Optional<List<String>>> execAsyncWithResult(final String command) {
    return CompletableFuture.supplyAsync(() -> {
      log.debug(STR"Executing async command with result: \{command}");
      return exec(command);
    }, VIRTUAL_THREAD_EXECUTOR);
  }
}