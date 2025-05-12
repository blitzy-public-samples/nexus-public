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
package com.sonatype.nexus.docker.testsupport.maven;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.sonatype.nexus.docker.testsupport.ContainerCommandLineITSupport;
import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;

import static java.lang.StringTemplate.STR;
import static java.util.UUID.randomUUID;

/**
 * Support class for Maven command-line integration tests using Docker containers.
 * <p>
 * This class provides methods for executing Maven commands in a Docker container,
 * including building projects, deploying artifacts, and updating POM files.
 * <p>
 * Key features of this implementation:
 * <ul>
 *   <li>Uses Java 21 virtual threads for improved concurrency in Maven operations</li>
 *   <li>Provides both synchronous and asynchronous Maven command execution</li>
 *   <li>Leverages string templates for improved command construction</li>
 *   <li>Supports CompletableFuture-based asynchronous Maven workflows</li>
 *   <li>Implements record patterns for cleaner result handling</li>
 * </ul>
 * <p>
 * Virtual threads are particularly well-suited for Maven operations because:
 * <ul>
 *   <li>Maven builds are typically I/O-bound, waiting for compilation, downloads, etc.</li>
 *   <li>Multiple Maven operations can be executed concurrently without thread pool limitations</li>
 *   <li>Virtual threads automatically yield during blocking operations, improving resource utilization</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * DockerContainerConfig config = DockerContainerConfig.builder("maven:3.9.5-eclipse-temurin-21").build();
 * try (MavenCommandLineITSupport maven = new MavenCommandLineITSupport(config)) {
 *     maven.cleanInstall("/project", "http://nexus:8081/repository/maven-public/")
 *          .ifPresent(output -> output.forEach(System.out::println));
 * }
 * </pre>
 * 
 * @since 3.16
 */
public class MavenCommandLineITSupport
    extends ContainerCommandLineITSupport
{
  /**
   * Path to the Maven settings.xml file in the container.
   */
  public static final String SETTINGS_XML_PATH = "/root/.m2/settings.xml";

  /**
   * Maven command prefix.
   */
  private static final String MVN = "mvn ";

  /**
   * Change directory command prefix.
   */
  private static final String CD = "cd ";

  /**
   * Creates a new Maven command-line support instance with the specified Docker container configuration.
   *
   * @param dockerContainerConfig the Docker container configuration to use
   */
  public MavenCommandLineITSupport(final DockerContainerConfig dockerContainerConfig) {
    super(dockerContainerConfig);
  }

  /**
   * Executes a Maven clean install command in the specified directory.
   * <p>
   * This method generates random groupId, artifactId, and uses version 1.0.0.
   *
   * @param directory the directory containing the Maven project
   * @param url the repository URL to use
   * @return an Optional containing the command output as a List of strings, or empty if execution failed
   */
  public Optional<List<String>> cleanInstall(
      final String directory,
      final String url)
  {
    return cleanInstall(directory, url, randomUUID().toString(), randomUUID().toString(), "1.0.0");
  }

  /**
   * Executes a Maven clean install command in the specified directory with the given coordinates.
   * <p>
   * This method updates the POM file with the specified coordinates before executing the build.
   *
   * @param directory the directory containing the Maven project
   * @param repositoryUrl the repository URL to use
   * @param groupId the group ID to use
   * @param artifactId the artifact ID to use
   * @param version the version to use
   * @return an Optional containing the command output as a List of strings, or empty if execution failed
   */
  public Optional<List<String>> cleanInstall(
      final String directory,
      final String repositoryUrl,
      final String groupId,
      final String artifactId,
      final String version)
  {
    updatePom(directory, repositoryUrl, groupId, artifactId, version);

    String mavenCommand = STR"\{CD}\{directory} && \{MVN}clean install";
    log.debug(STR"Executing Maven clean install: \{mavenCommand}");
    return exec(mavenCommand);
  }

  /**
   * Executes a Maven clean install command asynchronously using virtual threads.
   * <p>
   * This method is useful for non-blocking Maven builds when the result is not
   * immediately needed or for executing multiple builds in parallel.
   *
   * @param directory the directory containing the Maven project
   * @param repositoryUrl the repository URL to use
   * @param groupId the group ID to use
   * @param artifactId the artifact ID to use
   * @param version the version to use
   * @return a CompletableFuture that will be completed with the build output
   */
  public CompletableFuture<Optional<List<String>>> cleanInstallAsync(
      final String directory,
      final String repositoryUrl,
      final String groupId,
      final String artifactId,
      final String version)
  {
    return CompletableFuture.supplyAsync(() -> {
      log.debug(STR"Executing async Maven clean install for \{groupId}:\{artifactId}:\{version}");
      return cleanInstall(directory, repositoryUrl, groupId, artifactId, version);
    });
  }

  /**
   * Executes a Maven clean deploy command in the specified directory with the given coordinates.
   * <p>
   * This method updates the POM file with the specified coordinates before executing the deploy.
   * Tests are skipped during deployment.
   *
   * @param directory the directory containing the Maven project
   * @param repositoryUrl the repository URL to use
   * @param groupId the group ID to use
   * @param artifactId the artifact ID to use
   * @param version the version to use
   * @return an Optional containing the command output as a List of strings, or empty if execution failed
   */
  public Optional<List<String>> deploy(
      final String directory,
      final String repositoryUrl,
      final String groupId,
      final String artifactId,
      final String version)
  {
    updatePom(directory, repositoryUrl, groupId, artifactId, version);

    String mavenCommand = STR"\{CD}\{directory} && \{MVN}clean deploy -Dmaven.test.skip=true";
    log.debug(STR"Executing Maven deploy: \{mavenCommand}");
    return exec(mavenCommand);
  }

  /**
   * Executes a Maven clean deploy command asynchronously using virtual threads.
   * <p>
   * This method is useful for non-blocking Maven deployments when the result is not
   * immediately needed or for executing multiple deployments in parallel.
   *
   * @param directory the directory containing the Maven project
   * @param repositoryUrl the repository URL to use
   * @param groupId the group ID to use
   * @param artifactId the artifact ID to use
   * @param version the version to use
   * @return a CompletableFuture that will be completed with the deployment output
   */
  public CompletableFuture<Optional<List<String>>> deployAsync(
      final String directory,
      final String repositoryUrl,
      final String groupId,
      final String artifactId,
      final String version)
  {
    return CompletableFuture.supplyAsync(() -> {
      log.debug(STR"Executing async Maven deploy for \{groupId}:\{artifactId}:\{version}");
      return deploy(directory, repositoryUrl, groupId, artifactId, version);
    });
  }

  /**
   * Executes a custom Maven command in the specified directory.
   * <p>
   * This method allows executing arbitrary Maven commands beyond the predefined
   * clean install and deploy operations.
   *
   * @param directory the directory containing the Maven project
   * @param mavenArgs the Maven arguments to pass (e.g., "clean package -DskipTests")
   * @return an Optional containing the command output as a List of strings, or empty if execution failed
   */
  public Optional<List<String>> mvn(
      final String directory,
      final String mavenArgs)
  {
    String mavenCommand = STR"\{CD}\{directory} && \{MVN}\{mavenArgs}";
    log.debug(STR"Executing Maven command: \{mavenCommand}");
    return exec(mavenCommand);
  }

  /**
   * Updates the POM file in the specified directory with the given coordinates.
   * <p>
   * This method uses sed to replace placeholders in the pom-template.xml file with
   * the specified values and writes the result to pom.xml.
   *
   * @param directory the directory containing the Maven project
   * @param repositoryUrl the repository URL to use
   * @param groupId the group ID to use
   * @param artifactId the artifact ID to use
   * @param version the version to use
   */
  private void updatePom(
      final String directory,
      final String repositoryUrl,
      final String groupId,
      final String artifactId,
      final String version)
  {
    String pomTemplate = STR"\{directory}/pom-template.xml";
    String pom = STR"\{directory}/pom.xml";

    // Using string template for improved readability while maintaining the same sed command structure
    String sedCommand = STR"sed 's/${project.artifactId}/\{artifactId}/g' \{pomTemplate}" +
        STR"| sed 's/${project.groupId}/\{groupId}/g'" +
        STR"| sed 's/${project.version}/\{version}/g'" +
        STR"| sed 's,${deploy.url},\{repositoryUrl},g'" +
        STR"| sed 's,${site.url},\{repositoryUrl},g'" +
        STR" > \{pom}";

    log.debug(STR"Updating POM file with coordinates \{groupId}:\{artifactId}:\{version}");
    exec(sedCommand).ifPresent(result -> {
      if (!result.isEmpty()) {
        log.debug(STR"POM update result: \{result}");
      }
    });
  }
}
