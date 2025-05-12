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
package org.sonatype.nexus.testsuite.testsupport.raw;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.inject.Inject;

import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.testsuite.testsupport.RepositoryITSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.Tag;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.bytes;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.status;

/**
 * Support class for raw ITs.
 * <p>
 * This class provides support for Raw repository integration tests and has been updated for
 * Java 21 compatibility. It uses JUnit Jupiter 5.10.1 annotations and can be used with the
 * virtual-threads Maven profile to validate Virtual Thread behavior with Raw repositories.
 * </p>
 * <p>
 * When running with the virtual-threads profile activated, tests will use Virtual Threads for
 * I/O operations, which can significantly improve performance for concurrent operations.
 * </p>
 * <p>
 * Example usage with Virtual Threads:
 * <pre>
 * {@code
 * @Test
 * void testConcurrentUploads() {
 *   // Test will use Virtual Threads when virtual-threads profile is active
 *   ExecutorService executor = TestExecutorServiceFactory.create();
 *   try {
 *     // Test implementation using executor
 *   } finally {
 *     executor.shutdown();
 *   }
 * }
 * }
 * </pre>
 * </p>
 */
@Tag(RawTestGroup.NAME)
@Tag(Java21TestGroup.NAME)
public class RawITSupport
    extends RepositoryITSupport
{
  @Inject
  protected LogManager logManager;

  @Inject
  protected RawTestHelper rawTestHelper;

  public RawITSupport() {
    testData.addDirectory(resolveBaseFile("target/it-resources/raw"));
  }

  /**
   * Reads content from the specified repository path.
   * <p>
   * This method is optimized for Java 21 and can leverage Virtual Threads when available.
   * </p>
   *
   * @param repository the repository to read from
   * @param path the path to read
   * @return the content or null if not found
   * @throws IOException if an I/O error occurs
   */
  protected Content read(final Repository repository, final String path) throws IOException {
    return rawTestHelper.read(repository, path);
  }

  /**
   * Asserts that all specified paths are readable from the repository.
   *
   * @param repository the repository to check
   * @param paths the paths to verify
   * @throws IOException if an I/O error occurs
   */
  protected void assertReadable(final Repository repository, final String... paths) throws IOException {
    for (String path : paths) {
      assertThat(path, read(repository, path), notNullValue());
    }
  }

  /**
   * Asserts that all specified paths are not readable from the repository.
   *
   * @param repository the repository to check
   * @param paths the paths to verify
   * @throws IOException if an I/O error occurs
   */
  protected void assertNotReadable(final Repository repository, final String... paths) throws IOException {
    for (String path : paths) {
      assertThat(path, read(repository, path), nullValue());
    }
  }

  /**
   * Uploads a file to the repository, verifies it can be downloaded, then deletes it and verifies it's gone.
   * <p>
   * This method is compatible with Java 21 and can benefit from Virtual Threads when available.
   * </p>
   *
   * @param rawClient the client to use for repository operations
   * @param file the file to upload
   * @throws Exception if an error occurs
   */
  protected void uploadAndDownload(final RawClient rawClient, final String file) throws Exception {
    final File testFile = resolveTestFile(file);
    final int response = rawClient.put(file, ContentType.TEXT_PLAIN, testFile);
    assertThat(response, is(HttpStatus.CREATED));

    assertThat(bytes(rawClient.get(file)), is(Files.readAllBytes(testFile.toPath())));

    assertThat(status(rawClient.delete(file)), is(HttpStatus.NO_CONTENT));

    assertThat("content should be deleted", status(rawClient.get(file)), is(HttpStatus.NOT_FOUND));
  }
}