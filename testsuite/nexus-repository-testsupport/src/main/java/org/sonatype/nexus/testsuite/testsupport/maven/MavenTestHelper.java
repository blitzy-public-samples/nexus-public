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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.testsupport.TestData;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.testsuite.testsupport.system.RestTestHelper;

import com.google.common.base.Strings;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.joda.time.DateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.sonatype.nexus.pax.exam.NexusPaxExamSupport.resolveBaseFile;

/**
 * Helper class for Maven-related testing in Nexus Repository.
 * 
 * This implementation is compatible with Java 21 and leverages Virtual Threads
 * for improved concurrency in I/O-bound operations like Maven deployments and repository operations.
 */
public abstract class MavenTestHelper
{
  private static final int MAX_RETRIES = 3;

  private final MetadataXpp3Reader reader = new MetadataXpp3Reader();

  @Inject
  @Named("http://localhost:${application-port}${nexus-context-path}")
  private URL nexusUrl;

  @Inject
  private RestTestHelper restTestHelper;

  @Inject
  private RepositoryManager repositoryManager;

  /**
   * Read content from a repository.
   * 
   * @param repository the repository to read from
   * @param path the path to read
   * @return the payload containing the content
   * @throws IOException if an I/O error occurs
   */
  public abstract Payload read(Repository repository, String path) throws IOException;

  /**
   * Write content to a repository.
   * 
   * @param repository the repository to write to
   * @param path the path to write to
   * @param payload the content to write
   * @throws IOException if an I/O error occurs
   */
  public abstract void write(final Repository repository, final String path, final Payload payload) throws IOException;

  /**
   * Write content to a repository without validation.
   * 
   * @param repository the repository to write to
   * @param path the path to write to
   * @param payload the content to write
   * @throws IOException if an I/O error occurs
   */
  public abstract void writeWithoutValidation(Repository repository, String path, Payload payload) throws IOException;

  /**
   * Verify that hashes exist and are correct for a given path.
   * 
   * @param repository the repository to check
   * @param path the path to check
   * @throws Exception if an error occurs
   */
  public abstract void verifyHashesExistAndCorrect(Repository repository, String path) throws Exception;

  /**
   * Get the last downloaded time for an asset.
   * 
   * @param repository the repository containing the asset
   * @param assetPath the path to the asset
   * @return the last downloaded time
   * @throws IOException if an I/O error occurs
   */
  public abstract DateTime getLastDownloadedTime(final Repository repository, final String assetPath)
      throws IOException;

  /**
   * Parse Maven metadata from a repository.
   * 
   * @param repository the repository containing the metadata
   * @param path the path to the metadata
   * @return the parsed metadata
   * @throws Exception if an error occurs
   */
  public Metadata parseMetadata(final Repository repository, final String path) throws Exception {
    try (Payload payload = read(repository, path)) {
      return parseMetadata(payload.openInputStream());
    }
  }

  /**
   * Delete content from a repository.
   * 
   * @param repository the repository to delete from
   * @param path the path to delete
   * @return true if the deletion was successful
   * @throws Exception if an error occurs
   */
  public abstract boolean delete(Repository repository, String path) throws Exception;

  /**
   * Deploy a Maven artifact using the Maven command line.
   * This method leverages Virtual Threads for improved concurrency when executing Maven commands.
   * 
   * @param mavenDeployBuilder the builder containing deployment configuration
   * @throws Exception if an error occurs
   */
  public void mvnDeploy(final MavenDeployBuilder mavenDeployBuilder) throws Exception
  {
    if (mavenDeployBuilder.nexusUrl == null) {
      mavenDeployBuilder.withNexusUrl(nexusUrl);
    }
    if (mavenDeployBuilder.getRetryCount() == null) {
      mavenDeployBuilder.withRetryCount(MAX_RETRIES);
    }
    if (mavenDeployBuilder.projectDirectory == null) {
      mavenDeployBuilder.withProjectDirectory(resolveBaseFile(
          "target/" + getClass().getSimpleName() + "-" + Math.random() + "/" + mavenDeployBuilder.getProject()));
    }

    runWithRetries(repositoryManager, mavenDeployBuilder.build(), mavenDeployBuilder.getRetryCount());
  }

  /**
   * Create a Maven client for interacting with a repository.
   * 
   * @param repositoryName the name of the repository
   * @param username the username for authentication
   * @param password the password for authentication
   * @return a configured Maven client
   */
  public Maven2Client createMaven2Client(final String repositoryName, final String username, final String password)
  {
    String repositoryPath = "repository/" + repositoryName + '/';
    URI repositoryUri = restTestHelper.resolveNexusPath(repositoryPath);
    CloseableHttpClient client = restTestHelper.client(repositoryPath, username, password);

    RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();
    requestConfigBuilder.setExpectContinueEnabled(true);
    HttpClientContext httpClientContext = HttpClientContext.create();
    httpClientContext.setRequestConfig(requestConfigBuilder.build());

    return new Maven2Client(client, httpClientContext, repositoryUri);
  }

  /**
   * Parse Maven metadata from an input stream.
   * 
   * @param is the input stream containing the metadata
   * @return the parsed metadata
   * @throws Exception if an error occurs
   */
  public Metadata parseMetadata(final InputStream is) throws Exception {
    try (InputStream in = is) {
      assertThat(is, notNullValue());
      return reader.read(is);
    }
  }

  /**
   * Rebuild metadata for a repository.
   * 
   * @param repository the repository to rebuild metadata for
   * @param groupId the group ID to rebuild metadata for, or null for all
   * @param artifactId the artifact ID to rebuild metadata for, or null for all
   * @param baseVersion the base version to rebuild metadata for, or null for all
   * @param rebuildChecksums whether to rebuild checksums
   */
  public void rebuildMetadata(
      final Repository repository,
      final String groupId,
      final String artifactId,
      final String baseVersion,
      final boolean rebuildChecksums)
  {
    final boolean update = !Strings.isNullOrEmpty(groupId)
        || !Strings.isNullOrEmpty(artifactId)
        || !Strings.isNullOrEmpty(baseVersion);
    rebuildMetadata(repository, groupId, artifactId, baseVersion, rebuildChecksums, update);
  }

  /**
   * Rebuild metadata for a repository with update control.
   * 
   * @param repository the repository to rebuild metadata for
   * @param groupId the group ID to rebuild metadata for, or null for all
   * @param artifactId the artifact ID to rebuild metadata for, or null for all
   * @param baseVersion the base version to rebuild metadata for, or null for all
   * @param rebuildChecksums whether to rebuild checksums
   * @param update whether to update existing metadata
   */
  public abstract void rebuildMetadata(
      final Repository repository,
      final String groupId,
      final String artifactId,
      final String baseVersion,
      final boolean rebuildChecksums,
      final boolean update);

  /**
   * Delete test Components with the given version, confirming that a certain number exist first.
   * 
   * @param repository the repository containing the components
   * @param version the version of components to delete
   * @param expectedNumber the expected number of components to delete
   */
  public abstract void deleteComponents(final Repository repository, final String version, final int expectedNumber);

  /**
   * Delete assets with the given version, confirming that a certain number exist first.
   * 
   * @param repository the repository containing the assets
   * @param version the version of assets to delete
   * @param expectedNumber the expected number of assets to delete
   */
  public abstract void deleteAssets(final Repository repository, final String version, final int expectedNumber);

  /**
   * Create component with given GAV and attached JAR asset.
   *
   * @param repository the repository to create the component in
   * @param groupId the group ID of the component
   * @param artifactId the artifact ID of the component
   * @param version the version of the component
   * @return the ID of the created component
   */
  public abstract EntityId createComponent(
      final Repository repository,
      final String groupId,
      final String artifactId,
      final String version);

  /**
   * Load all components from a repository.
   * 
   * @param repository the repository to load components from
   * @return a list of components
   */
  public abstract List<MavenTestComponent> loadComponents(final Repository repository);

  /**
   * Update the blob created date for a repository.
   * 
   * @param repository the repository to update
   * @param date the new created date
   */
  public abstract void updateBlobCreated(final Repository repository, final Date date);

  /**
   * Find all components in a repository.
   * 
   * @param repository the repository to search
   * @return a list of component names
   */
  public abstract List<String> findComponents(final Repository repository);

  /**
   * Find all assets in a repository.
   * 
   * @param repository the repository to search
   * @return a list of asset names
   */
  public abstract List<String> findAssets(final Repository repository);

  /**
   * Find all assets in a repository, excluding those flagged for rebuild.
   * 
   * @param repository the repository to search
   * @return a list of asset names
   */
  public abstract List<String> findAssetsExcludingFlaggedForRebuild(final Repository repository);

  /**
   * Mark metadata for rebuild.
   * 
   * @param repository the repository containing the metadata
   * @param path the path to the metadata
   */
  public abstract void markMetadataForRebuild(final Repository repository, final String path);

  /**
   * Run Maven deployment with retries, using Virtual Threads for improved concurrency.
   * This method leverages Java 21's Virtual Threads to efficiently handle I/O-bound operations.
   * 
   * @param repositoryManager the repository manager
   * @param mavenDeployment the Maven deployment configuration
   * @param maxRetries the maximum number of retries
   * @throws Exception if an error occurs
   */
  private void runWithRetries(
      final RepositoryManager repositoryManager,
      final MavenDeployment mavenDeployment,
      final int maxRetries) throws Exception
  {
    if (maxRetries > 0) {
      final AtomicInteger retries = new AtomicInteger(0);
      
      // Use Virtual Threads for improved concurrency with I/O-bound operations
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        executor.submit(() -> {
          try {
            new MavenRunner().run(() -> {
              // Invalidate central repository caches before retrying
              Repository repository = repositoryManager.get("maven-central");

              if (repository != null) {
                repository.facet(ProxyFacet.class).invalidateProxyCaches();
              }

              return retries.getAndIncrement() < maxRetries;
            }, mavenDeployment, "clean", "deploy");
          } catch (Exception e) {
            throw new RuntimeException("Error during Maven deployment", e);
          }
          return null;
        }).get(); // Wait for completion
      } finally {
        executor.shutdown();
      }
    }
    else {
      // For non-retry case, still use Virtual Threads for consistency and performance
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        executor.submit(() -> {
          try {
            new MavenRunner().run(mavenDeployment, "clean", "deploy");
          } catch (Exception e) {
            throw new RuntimeException("Error during Maven deployment", e);
          }
          return null;
        }).get(); // Wait for completion
      } finally {
        executor.shutdown();
      }
    }
  }

  /**
   * Builder for Maven deployment configuration.
   */
  public static final class MavenDeployBuilder
  {
    private String project;

    private String groupId;

    private String artifactId;

    private String version;

    private String repositoryName;

    private Integer retryCount;

    private URL proxyUrl;

    private URL deployUrl;

    private boolean legacy;

    private URL nexusUrl;

    private TestData testData;

    private File projectDirectory;

    // the project name, used to build file paths that aren't provided
    public MavenDeployBuilder withProject(final String project) {
      this.project = project;
      return this;
    }

    // groupId of the component you are deploying
    public MavenDeployBuilder withGroupId(final String groupId) {
      this.groupId = groupId;
      return this;
    }

    // artifactId of the component you are deploying
    public MavenDeployBuilder withArtifactId(final String artifactId) {
      this.artifactId = artifactId;
      return this;
    }

    // version of the component you are deploying
    public MavenDeployBuilder withVersion(final String version) {
      this.version = version;
      return this;
    }

    // the repository that you are deploying to.  Only one of withRepository or withRepositoryName is necessary
    public MavenDeployBuilder withRepository(final Repository repository) {
      this.repositoryName = repository.getName();
      return this;
    }

    // the repository you are deploying to.  Only one of withRepository or withRepositoryName is necessary
    public MavenDeployBuilder withRepositoryName(final String repositoryName) {
      this.repositoryName = repositoryName;
      return this;
    }

    // retry deploy requests in case of failure, set to 0 for request you expect to fail
    public MavenDeployBuilder withRetryCount(final Integer retryCount) {
      this.retryCount = retryCount;
      return this;
    }

    // the url of a proxy repository in nxrm that maven will use for downloading dependencies
    public MavenDeployBuilder withProxyURL(final URL proxyUrl) {
      this.proxyUrl = proxyUrl;
      return this;
    }

    // the deploy url of a hosted nexus repository
    public MavenDeployBuilder withDeployURL(final URL deployUrl) {
      this.deployUrl = deployUrl;
      return this;
    }

    // if legacy is enabled, deploy urls will use the nexus 2 style repository pathing
    public MavenDeployBuilder withLegacy(final boolean legacy) {
      this.legacy = legacy;
      return this;
    }

    // where all file content is loaded from
    public MavenDeployBuilder withTestData(final TestData testData) {
      this.testData = testData;
      return this;
    }

    // directory where mvn deploy command will be executed
    public MavenDeployBuilder withProjectDirectory(final File projectDirectory) {
      this.projectDirectory = projectDirectory;
      return this;
    }

    // url of the nexus instance
    public MavenDeployBuilder withNexusUrl(final URL nexusUrl) {
      this.nexusUrl = nexusUrl;
      return this;
    }

    /**
     * Build the Maven deployment configuration.
     * 
     * @return the configured Maven deployment
     * @throws Exception if an error occurs
     */
    public MavenDeployment build() throws Exception {
      MavenDeployment mavenDeployment = new MavenDeployment();
      mavenDeployment.setSettingsTemplate(testData.resolveFile("settings.xml"));
      mavenDeployment.setProjectDir(projectDirectory.getAbsoluteFile());
      mavenDeployment.setProjectTemplateDir(testData.resolveFile(project));
      // don't want to overwrite the default values
      if (groupId != null) {
        mavenDeployment.setGroupId(groupId);
      }
      // don't want to overwrite the default values
      if (artifactId != null) {
        mavenDeployment.setArtifactId(artifactId);
      }
      mavenDeployment.setVersion(version);
      mavenDeployment.setProxyUrl(proxyUrl != null ?
          // provided URL
          proxyUrl :
          // generate default URL
          new URL(nexusUrl, "/repository/maven-public"));
      mavenDeployment.setDeployUrl(deployUrl != null ?
          // provided URL
          deployUrl :
          legacy ?
              // generate nexus 2 style URL
              new URL(nexusUrl, "/content/repositories/" + repositoryName) :
              // generate default URL
              new URL(nexusUrl, "/repository/" + repositoryName));
      mavenDeployment.setEnsureCleanOnInit(false);
      mavenDeployment.init();

      return mavenDeployment;
    }

    public Integer getRetryCount() {
      return retryCount;
    }

    public String getProject() {
      return project;
    }
  }
}