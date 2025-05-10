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

package org.sonatype.nexus.blobstore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class BlobStoreReconciliationLoggerTest
    extends TestSupport
{
  public static final String RECONCILIATION_LOG_DIRECTORY = "reconciliationLogDirectory";

  @TempDir
  Path temporaryFolder;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @Mock
  private BlobStore blobStore;

  @Mock
  private Logger logger;

  private MockedStatic<LoggerFactory> mockedStatic;

  @Mock
  private Path reconciliationLogPath;

  private BlobStoreReconciliationLogger underTest;

  @BeforeEach
  public void setUp() throws IOException {
    // mock blob store and its configuration
    BlobStoreConfiguration blobStoreConfiguration = mock(BlobStoreConfiguration.class);
    when(blobStoreConfiguration.getName()).thenReturn("blob-store-name");
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);

    // mock logger used to actually log blob ids
    mockedStatic = mockStatic(LoggerFactory.class);
    mockedStatic.when(() -> LoggerFactory.getLogger("blobstore-reconciliation-log")).thenReturn(logger);
    mockedStatic.when(() -> LoggerFactory.getLogger(BlobStoreReconciliationLogger.class))
        .thenReturn(mock(Logger.class));

    underTest = new BlobStoreReconciliationLogger(applicationDirectories);
  }

  @AfterEach
  public void teardown() {
    mockedStatic.close();
  }

  @Test
  public void shouldNotLogTemporaryBlobs() {
    underTest.logBlobCreated(reconciliationLogPath, new BlobId("tmp$00000000-0000-0000-0000-000000000000"));
    verifyNoInteractions(logger);
  }

  @Test
  public void shouldLogBlobId() {
    underTest.logBlobCreated(reconciliationLogPath, new BlobId("00000000-0000-0000-0000-000000000000"));

    verify(logger).info("{},{}", "00000000-0000-0000-0000-000000000000", false);

    mockedStatic.verify(() -> LoggerFactory.getLogger("blobstore-reconciliation-log"));
  }

  @Test
  public void shouldReadBlobIdsLoggedOnAndAfterRequestedDate() throws IOException {
    when(applicationDirectories
        .getWorkDirectory(RECONCILIATION_LOG_DIRECTORY))
        .thenReturn(temporaryFolder.toFile());
    Files.write(temporaryFolder.resolve("2021-04-13"),
        "2021-04-13 00:00:00,00000000-0000-0000-0000-000000000001".getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE);
    Files.write(temporaryFolder.resolve("2021-04-14"),
        ("2021-04-14 00:00:00,00000000-0000-0000-0000-000000000002\n" +
            "00000000-0000-0000-0000-000000000003\n" + // corrupted log line
            "2021-04-14 00:00:00,00000000-0000-0000-0000-000000000004\n").getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE);
    Files.write(temporaryFolder.resolve("2021-04-15"),
        "2021-04-15 00:00:00,00000000-0000-0000-0000-000000000005".getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE);
    // also put some unrelated file to verify it can skip over unrelated files without failing the reconcile process
    Files.write(temporaryFolder.resolve("2021-04-15-rubbish.bak"),
        "2021-04-14 00:00:00,00000000-0000-0000-0000-000000000006".getBytes(StandardCharsets.UTF_8),
        StandardOpenOption.CREATE);

    List<String> result = underTest.getBlobsCreatedSince(
        Paths.get(RECONCILIATION_LOG_DIRECTORY), LocalDateTime.parse("2021-04-14T00:00:00"),
            LocalDateTime.parse("2021-04-15T23:59:59.999999999") ,emptyMap())
        .map(BlobId::asUniqueString)
        .collect(toList());

    assertThat(result).hasSize(3);
    assertThat(result).containsExactlyInAnyOrder(
        "00000000-0000-0000-0000-000000000002",
        "00000000-0000-0000-0000-000000000004",
        "00000000-0000-0000-0000-000000000005");
  }

  @Test
  public void testDateBasedLayoutFlag() throws IOException {
    when(applicationDirectories
        .getWorkDirectory(RECONCILIATION_LOG_DIRECTORY))
        .thenReturn(temporaryFolder.toFile());

    Files.write(temporaryFolder.resolve("2024-05-01"),
        ("2024-05-01 01:00:00,00000000-0000-0000-0000-000000000001,true\n" +
         "2024-05-01 02:00:00,00000000-0000-0000-0000-000000000002,false\n" +
         "2024-05-01 03:00:00,00000000-0000-0000-0000-000000000003,true\n")
            .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE);

    List<String> result = underTest.getBlobsCreatedSince(
            Paths.get(RECONCILIATION_LOG_DIRECTORY), LocalDateTime.parse("2024-05-01T00:00:00"),
            LocalDateTime.parse("2024-05-01T23:59:59.999999999") ,emptyMap())
        .map(BlobId::asUniqueString)
        .collect(toList());

    // should return only 1 blob with vol/chap layout
    assertThat(result).hasSize(1);
    assertThat(result).containsExactly("00000000-0000-0000-0000-000000000002");
  }
}
