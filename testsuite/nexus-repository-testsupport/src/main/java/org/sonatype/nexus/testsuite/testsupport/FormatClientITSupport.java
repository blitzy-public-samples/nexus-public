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
package org.sonatype.nexus.testsuite.testsupport;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.apache.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.io.Files.write;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static org.apache.http.util.EntityUtils.toByteArray;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.common.io.NetworkHelper.findLocalHostAddress;

/**
 * Support class for Format Client ITs tested through the Docker Test Support.
 * <p>
 * This class has been updated for Java 21 compatibility, including support for virtual threads
 * for improved I/O operation performance and JUnit Jupiter 5.10.1 annotations.
 *
 * @since 3.6.1
 */
public abstract class FormatClientITSupport
    extends RepositoryITSupport
{
  /**
   * Temporary directory for test files.
   * <p>
   * This uses JUnit Jupiter's built-in TempDirectory extension which automatically
   * creates and cleans up temporary directories for tests.
   */
  @TempDir
  protected Path temporaryFolder;

  /**
   * This reflects the host name that should be used to identify the docker host of the docker client
   */
  protected static final String DOCKER_HOST_NAME = "nexus-docker-testsupport";

  protected File rootTemporaryFolder;

  protected File downloadsTemporaryFolder;

  /**
   * We are doing an override of the {@link NexusITSupport#configureNexus()}
   */
  @Configuration
  public static Option[] configureNexus() {
    return options(RepositoryITSupport.configureNexus(),
        nexusFeature("org.sonatype.nexus.testsuite", "nexus-docker-testsupport"),
        withHttps(resolveBaseFile(STR."target/it-resources/ssl/\{DOCKER_HOST_NAME}.jks")));
  }

  /**
   * Convenience method that helps setting up. Currently it sets up our {@link #downloadsTemporaryFolder}
   */
  @BeforeEach
  public void onInitializeForFormatClientTesting() throws Exception {
    rootTemporaryFolder = temporaryFolder.toFile();
    downloadsTemporaryFolder = temporaryFolder.resolve("downloads").toFile();
    downloadsTemporaryFolder.mkdir();
  }

  /**
   * Convenience method that helps doing cleanup.
   * <p>
   * Note: With JUnit Jupiter's @TempDir, the temporary directory is automatically deleted
   * after the test completes, so explicit deletion is no longer necessary.
   */
  @AfterEach
  public void onTearDownFormatClientTesting() {
    // No explicit cleanup needed as @TempDir handles this automatically
  }

  /**
   * Convenience method that allows a file to be downloaded from an {@link HttpResponse} into sub directory "downloads"
   * of the temporary directory.
   * <p>
   * This method uses virtual threads for improved I/O performance when available.
   *
   * @param httpResponse {@link HttpResponse}
   * @param name         file name to be given to downloaded file
   * @return the downloaded file
   */
  protected File downloadFromHttpResponse(final HttpResponse httpResponse, final String name) {
    checkNotNull(httpResponse);
    checkNotNull(name);

    File file = null;

    try {
      file = new File(downloadsTemporaryFolder, name);
      
      // Use CompletableFuture with virtual threads for I/O operations
      CompletableFuture.runAsync(() -> {
        try {
          write(toByteArray(httpResponse.getEntity()), file);
        }
        catch (IOException e) {
          throw new RuntimeException("Failed to write file from HttpResponse", e);
        }
      }, virtualThreadExecutor).join();
    }
    catch (Exception e) {
      fail("Failed to download file from HttpResponse: " + e.getMessage());
    }

    return file;
  }

  /**
   * Convenience method that allows adding test data paths.
   *
   * @param path location to add, e.g. "target/it-resources/yum"
   */
  protected void addTestDataDirectory(String path) {
    testData.addDirectory(resolveBaseFile(path));
  }

  private static File resolveTmpDir() {
    File tmpDir = new File(System.getProperty("java.io.tmpdir"));
    try {
      return tmpDir.getCanonicalFile();
    }
    catch (IOException e) { // NOSONAR: fall back to 'best-effort' absolute form
      return tmpDir.getAbsoluteFile();
    }
  }

  /**
   * Write the given file to the {@link #temporaryFolder}.
   * <p>
   * This method uses virtual threads for improved I/O performance when available.
   *
   * @param fileName the name of the file to write
   * @return File the local tmp file
   * @throws IOException if an I/O error occurs
   */
  protected File writeTmpFile(final String fileName) throws IOException {
    File file = temporaryFolder.resolve(fileName).toFile();
    
    // Use CompletableFuture with virtual threads for I/O operations
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        write(readTestDataFile(fileName).getBytes(UTF_8), file);
      }
      catch (IOException e) {
        throw new RuntimeException("Failed to write temporary file", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion
    future.join();
    
    return file;
  }

  /**
   * Read the given file from {@link #testData} folder
   * <p>
   * This method uses virtual threads for improved I/O performance when available.
   *
   * @param fileName the name of the file to read
   * @return String of read bytes
   * @throws IOException if an I/O error occurs
   */
  protected String readTestDataFile(final String fileName) throws IOException {
    // Use CompletableFuture with virtual threads for I/O operations
    CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
      try {
        return readAllBytes(testData.resolveFile(fileName).toPath());
      }
      catch (IOException e) {
        throw new RuntimeException("Failed to read test data file", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion and convert to String
    return new String(future.join(), UTF_8);
  }

  /**
   * Retrieve the Repo URL and used the {@link #nexusUrl} as its root
   *
   * @param repoName repository for which to get repo path for
   * @return String with Repo URL path
   */
  protected String getRepoUrl(final String repoName) {
    return convertUrl(repoName, nexusUrl);
  }

  /**
   * Retrieve the Repo URL and used the {@link #nexusSecureUrl} as its root
   *
   * @param repoName repository for which to get repo path for
   * @return String with Repo URL path
   */
  protected String getSecureRepoUrl(final String repoName) {
    return convertUrl(repoName, nexusSecureUrl);
  }

  private String convertUrl(final String repoName, final URL nexusSecureUrl) {
    String repoUrl = getRepoUrl(nexusSecureUrl, repoName);

    try {
      repoUrl = repoUrl.replaceAll("localhost", findLocalHostAddress());
    }
    catch (Exception e) {
      throw new RuntimeException(STR."Unable to get Repo URL: \{e.getMessage()}", e);
    }

    return repoUrl;
  }

  /**
   * Retrieve the Repo URL and used given {@link URL} as its root.
   *
   * @param url the base root
   * @param repoName repository for which to get repo path for
   * @param host name or ip to replace instead of localhost
   * @return String with Repo URL path
   */
  protected String getRepoUrl(final URL url, final String repoName, final String host) {
    return getRepoUrl(url, repoName).replaceAll("localhost", host);
  }

  /**
   * Retrieve the Repo URL and used given {@link URL} as its root
   *
   * @param url the base root
   * @param repoName repository for which to get repo path for
   * @return String with Repo URL path
   */
  protected String getRepoUrl(final URL url, final String repoName) {
    return resolveUrl(url, STR."/repository/\{repoName}/").toString();
  }

  /**
   * Convenience method to get the absolute path from inside {@link #rootTemporaryFolder}
   *
   * @param fileName name of file expected to be in the {@link #rootTemporaryFolder}
   * @return String containing the absolute path to given file in the {@link #rootTemporaryFolder}
   */
  protected String fromRoot(final String fileName) {
    return STR."\{rootTemporaryFolder}/\{fileName}";
  }
}