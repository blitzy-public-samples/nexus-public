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
package org.sonatype.nexus.repository.maven.internal.filter;

import java.nio.file.Path;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DuplicateDetectionStrategyProvider} with Java 21 and JUnit Jupiter.
 * 
 * Verifies that the provider correctly instantiates the appropriate strategy implementation
 * based on the configured strategy name, with case-insensitive matching and proper fallback
 * behavior for unknown strategy types.
 */
class DuplicateDetectionStrategyProviderTest
    extends TestSupport
{
  private static final int MAX_HEAP_GB = 1;

  private static final int MAX_DISK_SIZE_GB = 10;

  @TempDir
  Path tempDir;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @BeforeEach
  void setup() {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tempDir.toFile());
  }

  @Test
  void shouldReturnBloomStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories,
        "BLOOM", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();

    assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "bloom", MAX_HEAP_GB, MAX_DISK_SIZE_GB).get();

    assertThat(strategyLowerCase, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
  }

  @Test
  void shouldReturnDiskStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories, "DISK",
        MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(DiskBackedDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "disk", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategyLowerCase, is(instanceOf(DiskBackedDuplicateDetectionStrategy.class)));
  }

  @Test
  void shouldReturnInMemoryStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories, "HASH",
        MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(HashBasedDuplicateDetectionStrategy.class)));

    DuplicateDetectionStrategy<Record> strategyLowerCase = new DuplicateDetectionStrategyProvider(
        applicationDirectories, "hash", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategyLowerCase, is(instanceOf(HashBasedDuplicateDetectionStrategy.class)));
  }

  @Test
  void shouldFallBackToBloomForUnknownStrategy() {
    DuplicateDetectionStrategy<Record> strategy = new DuplicateDetectionStrategyProvider(applicationDirectories,
        "unknown", MAX_HEAP_GB, MAX_DISK_SIZE_GB)
        .get();

    assertThat(strategy, is(instanceOf(BloomFilterDuplicateDetectionStrategy.class)));
  }
}