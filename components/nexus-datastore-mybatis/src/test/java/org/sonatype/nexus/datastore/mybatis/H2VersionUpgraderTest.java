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
package org.sonatype.nexus.datastore.mybatis;

import java.io.File;
import java.util.Optional;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ManagedLifecycleManager;
import org.sonatype.nexus.common.io.FileFinder;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class H2VersionUpgraderTest
{
  @Mock
  private ApplicationDirectories directories;

  @Mock
  private ManagedLifecycleManager managedLifecycleManager;

  @InjectMocks
  private H2VersionUpgrader underTest;

  @Mock
  private HikariConfig hikariConfig;

  @BeforeEach
  void setUp() {
    // No need to manually initialize mocks or create underTest as MockitoExtension handles this
  }

  @Test
  void upgradeH2DatabaseWhenSqlFileNotPresent() throws Exception {
    when(directories.getWorkDirectory(any(String.class))).thenReturn(new File("/"));
    try (MockedStatic<FileFinder> utilities = Mockito.mockStatic(FileFinder.class)) {
      utilities.when(() -> FileFinder.findLatestTimestampedFile(any(), any(), any())).thenReturn(Optional.empty());
    }

    underTest.upgradeH2Database("testStore", hikariConfig);

    verify(managedLifecycleManager).shutdownWithExitCode(1);
  }
}