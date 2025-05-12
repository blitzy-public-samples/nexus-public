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
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;
import javax.inject.Inject;

import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.PathPayload;
import org.sonatype.nexus.testsuite.testsupport.RepositoryITSupport;
import org.sonatype.nexus.testsuite.testsupport.maven.MavenTestHelper.MavenDeployBuilder;

import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STORAGE;
import static org.sonatype.nexus.repository.config.ConfigurationConstants.STRICT_CONTENT_TYPE_VALIDATION;

/**
 * Maven IT support for Java 21.
 * 
 * This class provides integration test support for Maven repositories with Java 21 features,
 * including Virtual Threads for improved concurrency in I/O operations.
 */
@Tag("maven")
public abstract class MavenITSupport
    extends RepositoryITSupport
{
  /**
   * Executor service using Java 21 Virtual Threads for concurrent I/O operations.
   * Virtual Threads provide lightweight concurrency with significantly reduced overhead
   * compared to platform threads, making them ideal for I/O-bound operations.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  
  @Inject
  protected LogManager logManager;

  @Inject
  protected MavenTestHelper mavenTestHelper;

  public MavenITSupport() {
    testData.addDirectory(resolveBaseFile("target/it-resources/maven"));
  }

  @BeforeEach
  public void disableCentralAutoBlocking() throws Exception {
    //turn off central auto blocking, in case random error encountered
    Repository repository = repositoryManager.get("maven-central");
    if (repository != null) {
      Configuration updatedConfiguration = repository.getConfiguration().copy();
      updatedConfiguration.attributes("httpclient").set("autoBlock", false);
      repositoryManager.update(updatedConfiguration);
    }
  }

  protected File mvnBaseDir(final String project) {
    return resolveBaseFile("target/" + getClass().getSimpleName() + "-" + testName.getMethodName() + "/" + project);
  }

  /**
   * Deploy Maven artifacts using Virtual Threads for improved concurrency.
   * This method leverages Java 21's Virtual Threads to handle the I/O-bound operations
   * during Maven deployment, allowing for better scalability with minimal resource overhead.
   */
  public void mvnDeploy(final MavenDeployBuilder mavenDeployBuilder) throws Exception {
    CompletableFuture.runAsync(() -> {
      try {
        mavenTestHelper.mvnDeploy(mavenDeployBuilder.
            withTestData(testData).
            withNexusUrl(nexusUrl).
            withProjectDirectory(mvnBaseDir(mavenDeployBuilder.getProject())));
      }
      catch (Exception e) {
        throw new RuntimeException("Error during Maven deployment", e);
      }
    }, virtualThreadExecutor).join();
  }

  /**
   * Write content to a repository using Virtual Threads for improved I/O performance.
   */
  protected void write(final Repository repository, final String path, final Payload payload) throws IOException {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        mavenTestHelper.write(repository, path, payload);
      }
      catch (IOException e) {
        throw new RuntimeException("Error writing to repository", e);
      }
    }, virtualThreadExecutor);
    
    try {
      future.join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * We added validation in NEXUS-16853 that prevents corrupted metadata files being stored in the repository however
   * some ITs test the ability to recover from corrupted metadata and therefore need to bypass the validation.
   * 
   * This implementation uses Virtual Threads for improved I/O performance.
   */
  protected void writeWithoutValidation(final Repository repository, final String path, final Payload payload)
      throws IOException
  {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        mavenTestHelper.writeWithoutValidation(repository, path, payload);
      }
      catch (IOException e) {
        throw new RuntimeException("Error writing to repository without validation", e);
      }
    }, virtualThreadExecutor);
    
    try {
      future.join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  protected Payload filePayload(final File file, final String contentType) {
    return new PathPayload(file.toPath(), contentType);
  }

  /**
   * Read content from a repository using Virtual Threads for improved I/O performance.
   */
  protected Payload read(final Repository repository, final String path) throws IOException {
    CompletableFuture<Payload> future = CompletableFuture.supplyAsync(() -> {
      try {
        return mavenTestHelper.read(repository, path);
      }
      catch (IOException e) {
        throw new RuntimeException("Error reading from repository", e);
      }
    }, virtualThreadExecutor);
    
    try {
      return future.join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw e;
    }
  }

  protected void assertReadable(final Repository repository, final String... paths) throws IOException {
    for (String path : paths) {
      assertThat(path, read(repository, path), notNullValue());
    }
  }

  protected void assertNotReadable(final Repository repository, final String... paths) throws IOException {
    for (String path : paths) {
      assertThat(path, read(repository, path), nullValue());
    }
  }

  protected Metadata parseMetadata(final InputStream is) throws Exception {
    assertThat(is, notNullValue());
    return mavenTestHelper.parseMetadata(is);
  }

  protected Metadata parseMetadata(final Payload content) throws Exception {
    assertThat(content, notNullValue());
    try (InputStream is = content.openInputStream()) {
      return parseMetadata(is);
    }
  }

  @Nonnull
  protected Repository redirectProxy(final String repositoryName, final String remoteUrl) throws Exception {
    checkNotNull(repositoryName);
    Repository proxy = repositoryManager.get(repositoryName);
    checkNotNull(proxy);
    checkArgument(ProxyType.NAME.equals(proxy.getType().getValue()));
    org.sonatype.nexus.repository.config.Configuration proxyConfiguration = proxy.getConfiguration().copy();
    proxyConfiguration.attributes("proxy").set("remoteUrl", remoteUrl);
    proxyConfiguration.attributes(STORAGE).set(STRICT_CONTENT_TYPE_VALIDATION, true);
    return repositoryManager.update(proxyConfiguration);
  }

  @Nonnull
  protected Maven2Client createAdminMaven2Client(final Repository repo) throws Exception {
    return createAdminMaven2Client(repo.getName());
  }

  @Nonnull
  protected Maven2Client createAdminMaven2Client(final String repositoryName) throws Exception {
    return createMaven2Client(repositoryName, "admin", "admin123");
  }

  protected Maven2Client createAdminMaven2Client(final URL repositoryUrl) throws Exception {
    return createMaven2Client(repositoryUrl, "admin", "admin123");
  }

  @Nonnull
  protected Maven2Client createMaven2Client(final String repositoryName, final String username, final String password)
      throws Exception
  {
    checkNotNull(repositoryName);
    Repository repository = repositoryManager.get(repositoryName);
    checkNotNull(repository);
    return createMaven2Client(resolveUrl(nexusUrl, "/repository/" + repositoryName + "/"), username, password);
  }

  /**
   * Create a Maven2Client with HTTP client configured for optimal performance with Java 21.
   */
  protected Maven2Client createMaven2Client(final URL repositoryUrl, final String username, final String password)
      throws Exception
  {
    HttpClientBuilder client = HttpClients.custom();
    if (username != null) {
      AuthScope scope = new AuthScope(repositoryUrl.getHost(), -1);
      CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
      credentialsProvider.setCredentials(scope, new UsernamePasswordCredentials(username, password));
      client.setDefaultCredentialsProvider(credentialsProvider);
    }

    RequestConfig.Builder requestConfigBuilder = RequestConfig.custom();
    requestConfigBuilder.setExpectContinueEnabled(true);
    HttpClientContext httpClientContext = HttpClientContext.create();
    httpClientContext.setRequestConfig(requestConfigBuilder.build());
    return new Maven2Client(
        client.build(),
        httpClientContext,
        repositoryUrl.toURI()
    );
  }

  /**
   * Verify hashes exist and are correct using Virtual Threads for improved I/O performance.
   */
  protected void verifyHashesExistAndCorrect(final Repository repository, final String path) throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        mavenTestHelper.verifyHashesExistAndCorrect(repository, path);
      }
      catch (Exception e) {
        throw new RuntimeException("Error verifying hashes", e);
      }
    }, virtualThreadExecutor);
    
    try {
      future.join();
    }
    catch (RuntimeException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }

  protected List<MavenTestComponent> loadComponents(final Repository repository) {
    return mavenTestHelper.loadComponents(repository);
  }

  protected void updateLastUpdated(final Repository repository, final Date date) {
    componentAssetTestHelper.setComponentLastUpdatedTime(repository, date);
  }

  protected void setBlobUpdatedTime(final Repository repository, final String pathRegex, final Date date) {
    componentAssetTestHelper.setBlobUpdatedTime(repository, pathRegex, date);
  }

}