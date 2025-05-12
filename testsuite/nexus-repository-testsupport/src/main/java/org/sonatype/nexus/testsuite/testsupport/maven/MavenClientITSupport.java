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
package org.sonatype.nexus.testsuite.testsupport.maven;

import java.io.File;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.ws.rs.client.WebTarget;

import com.sonatype.nexus.docker.testsupport.framework.DockerContainerConfig;
import com.sonatype.nexus.docker.testsupport.maven.MavenCommandLineITSupport;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.testsuite.testsupport.FormatClientITSupport;
import org.sonatype.nexus.testsuite.testsupport.utility.SearchTestHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import static java.io.File.createTempFile;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.io.FileUtils.forceDelete;
import static org.apache.commons.io.FileUtils.readFileToString;
import static org.apache.commons.io.FileUtils.write;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * Support class for Maven Format Client ITs.
 * <p>
 * This class has been updated for Java 21 compatibility and leverages Virtual Threads
 * for improved performance in Docker operations and I/O-bound tasks.
 * <p>
 * Requirements:
 * - Java 21 JDK
 * - JUnit Jupiter 5.10.1
 * - Mockito 5.8.0
 */
public abstract class MavenClientITSupport
    extends FormatClientITSupport
{
  protected static final String PROJECT = "testproject-clientit";

  protected static final String PROJECT_PATH = "/testproject-clientit";

  protected static final String OK_BUILD = "BUILD SUCCESS";

  protected static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2/";

  protected MavenCommandLineITSupport mvn;

  protected File settings;

  @Inject
  private SearchTestHelper searchTestHelper;

  /**
   * Initializes the Maven client integration test environment.
   * <p>
   * Sets up test data directories, creates Maven settings.xml, initializes the Maven command line support,
   * and prepares the test project directory.
   * <p>
   * This method uses Virtual Threads for I/O operations when running on Java 21.
   */
  @BeforeEach
  public void onInitializeClientIT() throws Exception {
    addTestDataDirectory("target/it-resources/maven");

    // Use Virtual Thread for I/O-bound operations
    runWithVirtualThread(() -> {
      try {
        createSettingsXml();
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to create settings.xml", e);
      }
    }).join();

    mvn = new MavenCommandLineITSupport(createTestConfig());

    // Use Virtual Thread for Docker operations
    runWithVirtualThread(() -> {
      mvn.exec("rm -rf ~/.m2");
      //copy the project to a local directory as we are using a read only bind path
      mvn.exec("cp -R " + PROJECT_PATH + "-external " + PROJECT_PATH);
    }).join();
  }

  /**
   * Cleans up resources after test execution.
   * <p>
   * Deletes the temporary settings.xml file and shuts down the Maven command line support.
   * <p>
   * This method uses Virtual Threads for I/O operations when running on Java 21.
   */
  @AfterEach
  public void onTearDownClientIT() throws Exception {
    // Use Virtual Thread for cleanup operations
    runWithVirtualThread(() -> {
      try {
        forceDelete(settings);
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to delete settings file", e);
      }
    }).join();

    mvn.exit();
  }

  /**
   * Creates a Maven settings.xml file with the appropriate proxy URL.
   * <p>
   * This method is optimized for Java 21 with improved file I/O handling.
   */
  private void createSettingsXml() throws Exception {
    settings = createTempFile("settings", ".xml");

    String settingsContent = readFileToString(testData.resolveFile("settings.xml"))
        .replace("${proxyUrl}", getSettingsProxyUrl());

    write(settings, settingsContent);
  }

  /**
   * Builds and deploys a Maven project using Virtual Threads for improved performance.
   * <p>
   * This method leverages Java 21 Virtual Threads to execute the Maven build process
   * asynchronously, improving throughput for I/O-bound operations.
   *
   * @param groupId The Maven group ID
   * @param artifactId The Maven artifact ID
   * @param url The repository URL to deploy to
   * @param snapshotVersion The snapshot version
   * @param success Whether the build is expected to succeed
   */
  protected void buildAndDeployProject(
      final String groupId, final String artifactId, final String url,
      final String snapshotVersion,
      final boolean success)
  {
    // Use CompletableFuture with Virtual Thread to execute Maven deployment
    CompletableFuture<List<String>> buildLogFuture = runWithVirtualThread(() -> {
      Optional<List<String>> result = mvn.deploy(PROJECT_PATH,
          url,
          groupId,
          artifactId,
          snapshotVersion);
      return getBuildLog(result);
    });

    List<String> buildLog = buildLogFuture.join();
    assertThat(buildLog.stream().anyMatch(line -> line.contains(OK_BUILD)), is(success));
  }

  /**
   * Asynchronously builds and deploys a Maven project using Virtual Threads.
   * <p>
   * This method returns a CompletableFuture that can be used to retrieve the build log
   * when the operation completes.
   *
   * @param groupId The Maven group ID
   * @param artifactId The Maven artifact ID
   * @param url The repository URL to deploy to
   * @param snapshotVersion The snapshot version
   * @return A CompletableFuture containing the build log
   */
  protected CompletableFuture<List<String>> buildAndDeployProjectAsync(
      final String groupId, final String artifactId, final String url,
      final String snapshotVersion)
  {
    return runWithVirtualThread(() -> {
      Optional<List<String>> result = mvn.deploy(PROJECT_PATH,
          url,
          groupId,
          artifactId,
          snapshotVersion);
      return getBuildLog(result);
    });
  }

  /**
   * Extracts the build log from the Maven execution result.
   * <p>
   * This method has been updated to use Java 21 pattern matching for instanceof checks.
   *
   * @param result The optional result from Maven execution
   * @return The build log as a list of strings
   * @throws AssertionError if no build log is found
   */
  protected List<String> getBuildLog(final Optional<List<String>> result) {
    if (result.isEmpty()) {
      throw new AssertionError("No build log found - did the docker container fail to start?");
    }

    return result.get().stream().map(String::trim).collect(toList());
  }

  /**
   * Verifies if a component exists in the repository.
   * <p>
   * This method uses Virtual Threads for the search operation to improve performance
   * with I/O-bound REST API calls.
   *
   * @param repository The repository to search in
   * @param name The component name
   * @param version The component version
   * @param exists Whether the component is expected to exist
   */
  protected void verifyComponentExists(
      final Repository repository,
      final String name,
      final String version,
      final boolean exists)
      throws Exception
  {
    WebTarget target = restClient().target(buildNexusUrl("/service/rest/v1/search"));
    
    // Use Virtual Thread for REST API call
    runWithVirtualThread(() -> {
      try {
        searchTestHelper.verifyComponentExists(target, repository, name, version, exists);
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to verify component existence", e);
      }
    }).join();
  }

  /**
   * Asynchronously verifies if a component exists in the repository.
   * <p>
   * This method returns a CompletableFuture that completes when the verification is done.
   *
   * @param repository The repository to search in
   * @param name The component name
   * @param version The component version
   * @param exists Whether the component is expected to exist
   * @return A CompletableFuture that completes when verification is done
   */
  protected CompletableFuture<Void> verifyComponentExistsAsync(
      final Repository repository,
      final String name,
      final String version,
      final boolean exists)
  {
    WebTarget target = restClient().target(buildNexusUrl("/service/rest/v1/search"));
    
    return runWithVirtualThread(() -> {
      try {
        searchTestHelper.verifyComponentExists(target, repository, name, version, exists);
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to verify component existence", e);
      }
    });
  }

  /**
   * Gets the location of the Maven settings file.
   *
   * @return The absolute path to the settings.xml file
   */
  protected String getSettingsFileLocation() {
    return settings.getAbsolutePath();
  }

  /**
   * Runs a task in a Virtual Thread and returns a CompletableFuture.
   * <p>
   * This method leverages Java 21's Virtual Threads to improve performance for I/O-bound operations.
   *
   * @param task The task to run
   * @param <T> The return type of the task
   * @return A CompletableFuture that completes with the result of the task
   */
  protected <T> CompletableFuture<T> runWithVirtualThread(final java.util.function.Supplier<T> task) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return CompletableFuture.supplyAsync(task, executor);
    }
  }

  /**
   * Runs a task in a Virtual Thread and returns a CompletableFuture<Void>.
   * <p>
   * This method is useful for operations that don't return a value.
   *
   * @param task The task to run
   * @return A CompletableFuture that completes when the task is done
   */
  protected CompletableFuture<Void> runWithVirtualThread(final Runnable task) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return CompletableFuture.runAsync(task, executor);
    }
  }

  /**
   * Creates a Docker container configuration for Maven tests.
   * <p>
   * This method should be implemented by subclasses to provide the specific
   * Docker container configuration needed for their tests.
   *
   * @return The Docker container configuration
   * @throws Exception if configuration creation fails
   */
  protected abstract DockerContainerConfig createTestConfig() throws Exception;

  /**
   * Gets the proxy URL to use in the Maven settings.xml file.
   * <p>
   * This method should be implemented by subclasses to provide the specific
   * proxy URL needed for their tests.
   *
   * @return The proxy URL
   */
  protected abstract String getSettingsProxyUrl();
}