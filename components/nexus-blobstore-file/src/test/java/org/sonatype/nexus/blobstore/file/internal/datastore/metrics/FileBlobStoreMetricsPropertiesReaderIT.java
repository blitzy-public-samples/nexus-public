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
package org.sonatype.nexus.blobstore.file.internal.datastore.metrics;

import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.internal.FileOperations;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.common.property.PropertiesFile;
import org.sonatype.nexus.content.testsuite.groups.SQLTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link FileBlobStoreMetricsPropertiesReader} integration tests.
 */
@ExtendWith(MockitoExtension.class)
@Tag("SQLTestGroup")
public class FileBlobStoreMetricsPropertiesReaderIT
    extends TestSupport
{
  private FileBlobStoreMetricsPropertiesReader underTest;

  private Path blobStoreDirectory;

  private static final int METRICS_FLUSH_TIMEOUT = 5;

  @Mock
  NodeAccess nodeAccess;

  @Mock
  FileBlobStore blobStore;

  @Mock
  FileOperations fileOperations;

  @BeforeEach
  public void setUp() {
    lenient().when(nodeAccess.getId()).thenReturn(UUID.randomUUID().toString());
    blobStoreDirectory = util.createTempDir().toPath();
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (underTest != null && underTest.isStarted()) {
      underTest.stop();
    }
  }

  @SuppressWarnings("java:S2699") // sonar doesn't detect assertions in awaitility
                                  // https://jira.sonarsource.com/browse/SONARJAVA-3334
  @Test
  public void shouldLoadMetricsFromExistingPropertyFile() throws Exception {
    PropertiesFile props = new PropertiesFile(
        blobStoreDirectory.resolve(nodeAccess.getId() + "-" + FileBlobStoreMetricsPropertiesReader.METRICS_FILENAME)
            .toFile());

    props.put(FileBlobStoreMetricsPropertiesReader.BLOB_COUNT_PROP_NAME, "32");
    props.put(FileBlobStoreMetricsPropertiesReader.TOTAL_SIZE_PROP_NAME, "200");

    props.store();

    init();

    await().atMost(METRICS_FLUSH_TIMEOUT, SECONDS).until(() -> underTest.getMetrics().getBlobCount(), is(32L));
    await().atMost(METRICS_FLUSH_TIMEOUT, SECONDS).until(() -> underTest.getMetrics().getTotalSize(), is(200L));
  }

  @Test
  public void shouldListBackingFiles() throws Exception {
    underTest = new FileBlobStoreMetricsPropertiesReader();
    Stream<PropertiesFile> backingFiles = underTest.backingFiles();
    assertThat("backing files is empty", backingFiles.count(), is(0L));

    lenient().when(blobStore.getAbsoluteBlobDir()).thenReturn(blobStoreDirectory);
    underTest.init(blobStore);

    PropertiesFile props = new PropertiesFile(
        blobStoreDirectory.resolve(nodeAccess.getId() + "-" + FileBlobStoreMetricsPropertiesReader.METRICS_FILENAME)
            .toFile());
    props.store();

    backingFiles = underTest.backingFiles();
    assertThat("backing files contains the data file", backingFiles.count(), is(1L));
  }

  private void init() throws Exception {
    init(blobStoreDirectory);
  }

  private void init(final Path path) throws Exception {
    underTest = new FileBlobStoreMetricsPropertiesReader();

    lenient().when(blobStore.getAbsoluteBlobDir()).thenReturn(path);
    underTest.init(blobStore);
  }
}
