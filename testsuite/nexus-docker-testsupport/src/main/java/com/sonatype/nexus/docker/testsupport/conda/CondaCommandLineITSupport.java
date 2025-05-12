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
package com.sonatype.nexus.docker.testsupport.conda;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sonatype.nexus.docker.testsupport.ContainerCommandLineITSupport;
import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

/**
 * Conda implementation of a Docker Command Line enabled container.
 * 
 * This class is compatible with Java 21 and leverages virtual threads for improved
 * test performance and scalability when executing multiple Conda commands concurrently.
 *
 * @since 3.19
 */
public class CondaCommandLineITSupport
    extends ContainerCommandLineITSupport
{
  private static final String CMD_CONDA = "conda ";
  
  /**
   * Executor service using virtual threads for concurrent command execution.
   * Virtual threads are lightweight threads that are managed by the JVM rather than the OS,
   * allowing for much higher concurrency with minimal resource overhead.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Constructor.
   *
   * @param dockerContainerConfig {@link DockerContainerConfig}
   */
  public CondaCommandLineITSupport(final DockerContainerConfig dockerContainerConfig) {
    super(dockerContainerConfig);
  }

  /**
   * Execute a conda command. I.e. a command that is always prefixed with {@link #CMD_CONDA}
   *
   * @see #exec(String)
   */
  public List<String> condaExec(final String s) {
    return exec(CMD_CONDA + s).orElse(emptyList());
  }
  
  /**
   * Execute a conda command asynchronously using virtual threads.
   * This allows for improved concurrency and performance when executing multiple commands.
   *
   * @param s the command to execute (without the conda prefix)
   * @return a CompletableFuture that will complete with the command output
   */
  public CompletableFuture<List<String>> condaExecAsync(final String s) {
    return CompletableFuture.supplyAsync(() -> condaExec(s), virtualThreadExecutor);
  }

  /**
   * Runs a <code>conda -y install</code>
   *
   * @param packageName name of the conda package to install
   * @return List of {@link String} of output from execution
   */
  public List<String> condaInstall(final String packageName) {
    return condaExec(format("install -y %s", packageName));
  }
  
  /**
   * Runs a <code>conda -y install</code> asynchronously using virtual threads.
   *
   * @param packageName name of the conda package to install
   * @return CompletableFuture with List of {@link String} of output from execution
   */
  public CompletableFuture<List<String>> condaInstallAsync(final String packageName) {
    return condaExecAsync(format("install -y %s", packageName));
  }

  /**
   * Runs a <code>conda list</code>
   *
   * @return List of {@link String} of output from execution
   */
  public List<String> listInstalled() {
    return clearTerminalOutputHeader(condaExec("list"));
  }
  
  /**
   * Runs a <code>conda list</code> asynchronously using virtual threads.
   *
   * @return CompletableFuture with List of {@link String} of output from execution
   */
  public CompletableFuture<List<String>> listInstalledAsync() {
    return condaExecAsync("list").thenApply(this::clearTerminalOutputHeader);
  }

  /**
   * Runs a <code>conda search</code> for packages
   *
   * @param name package name to search for
   * @return List of {@link String} of output from execution
   */
  public List<String> condaSearchPackages(final String name) {
    return clearTerminalOutputHeader(condaExec("search " + name));
  }
  
  /**
   * Runs a <code>conda search</code> for packages asynchronously using virtual threads.
   *
   * @param name package name to search for
   * @return CompletableFuture with List of {@link String} of output from execution
   */
  public CompletableFuture<List<String>> condaSearchPackagesAsync(final String name) {
    return condaExecAsync("search " + name).thenApply(this::clearTerminalOutputHeader);
  }

  /**
   * Remove package by name
   *
   * @param name name of the package
   * @return terminal output
   */
  public List<String> removePackage(final String name) {
    return condaExec("remove -y --name " + name);
  }
  
  /**
   * Remove package by name asynchronously using virtual threads.
   *
   * @param name name of the package
   * @return CompletableFuture with terminal output
   */
  public CompletableFuture<List<String>> removePackageAsync(final String name) {
    return condaExecAsync("remove -y --name " + name);
  }

  /**
   * Clean Conda client cache
   * 
   * @return List of {@link String} of output from execution
   */
  public List<String> clearClientCache() {
    return condaExec("clean -a -y"); // -a = all ; -y - do not ask accept
  }
  
  /**
   * Clean Conda client cache asynchronously using virtual threads.
   * 
   * @return CompletableFuture with List of {@link String} of output from execution
   */
  public CompletableFuture<List<String>> clearClientCacheAsync() {
    return condaExecAsync("clean -a -y"); // -a = all ; -y - do not ask accept
  }
  
  /**
   * Shutdown the virtual thread executor service.
   * This method should be called when the instance is no longer needed to ensure proper cleanup.
   */
  @Override
  public void exit() {
    try {
      virtualThreadExecutor.shutdown();
    }
    finally {
      super.exit();
    }
  }

  /**
   * Remove top header from the terminal output
   * 
   * Uses Java 21 pattern matching for Optional to simplify the code.
   */
  private List<String> clearTerminalOutputHeader(final List<String> terminalOutput) {
    Optional<String> header = terminalOutput.stream().filter(row -> row.contains("Name")).findFirst();
    
    // Using pattern matching for Optional (Java 21 feature)
    if (header instanceof Optional<String> opt && opt.isPresent()) {
      int headerIndex = terminalOutput.indexOf(opt.get());
      return new ArrayList<>(terminalOutput.subList(headerIndex + 1, terminalOutput.size()));
    }
    
    return emptyList();
  }
}