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

import java.io.File;
import java.nio.file.Path;

import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.when;

/**
 * Tests for {@link DiskBackedDuplicateDetectionStrategy} using a disk-backed cache for duplicate detection.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class DiskBackedDuplicateDetectionStrategyTest
    extends DuplicateDetectionStrategyTestSupport
{
  @TempDir
  Path tempDir;

  @Mock
  private ApplicationDirectories applicationDirectories;

  /**
   * Verifies that the disk-backed strategy correctly identifies duplicate artifacts.
   * 
   * @throws Exception if any error occurs during testing
   */
  @Test
  public void shouldIdentifyDuplicates() throws Exception {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tempDir.toFile());

    verifyDuplicateDetection(new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10));
  }
}