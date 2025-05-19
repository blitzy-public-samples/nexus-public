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
package org.sonatype.nexus.bootstrap.osgi;

import java.io.File;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.bootstrap.osgi.NexusEdition.NEXUS_LOAD_AS_OSS_PROP_NAME;
import static org.sonatype.nexus.bootstrap.osgi.NexusEdition.PRO_LICENSE_LOCATION;

@ExtendWith(MockitoExtension.class)
public class ProNexusEditionParameterizedTest
{
  @Spy
  private ProNexusEdition underTest = new ProNexusEdition();

  @Mock
  private Path workDirPath;

  @Mock
  private Path proPath;

  @Mock
  private File proEditionMarker;



  static Stream<Arguments> provideTestCases() {
    return Stream.of(
        // if nexus.loadAsOSS has a value and it's true then is_oss == true
        Arguments.of(true, true, false, true, true, false, false, false, true, false),
        Arguments.of(true, true, true, true, true, false, false, false, true, false),
        Arguments.of(true, true, false, true, true, false, false, false, true, false),
        Arguments.of(true, true, true, true, true, false, false, false, true, false),
        Arguments.of(true, true, false, false, true, false, false, false, true, false),
        Arguments.of(true, true, true, false, true, false, false, false, true, false),
        Arguments.of(true, true, false, false, true, false, false, false, true, false),
        Arguments.of(true, true, true, false, true, false, false, false, true, false),
        Arguments.of(true, true, false, true, false, false, false, false, true, false),
        Arguments.of(true, true, true, true, false, false, false, false, true, false),
        Arguments.of(true, true, false, true, false, false, false, false, true, false),
        Arguments.of(true, true, true, true, false, false, false, false, true, false),
        Arguments.of(true, true, false, false, false, false, false, false, true, false),
        Arguments.of(true, true, true, false, false, false, false, false, true, false),
        Arguments.of(true, true, false, false, false, false, false, false, true, false),
        Arguments.of(true, true, true, false, false, false, false, false, true, false),
        // if nexus.loadAsOSS has a value and it's false then is_oss == false
        Arguments.of(true, false, true, true, true, false, false, false, false, false),
        Arguments.of(true, false, false, true, true, false, false, false, false, false),
        Arguments.of(true, false, true, true, true, false, false, false, false, false),
        Arguments.of(true, false, false, true, true, false, false, false, false, false),
        Arguments.of(true, false, true, false, true, false, false, false, false, false),
        Arguments.of(true, false, false, false, true, false, false, false, false, false),
        Arguments.of(true, false, true, false, true, false, false, false, false, false),
        Arguments.of(true, false, false, false, true, false, false, false, false, false),
        Arguments.of(true, false, true, true, false, false, false, false, false, false),
        Arguments.of(true, false, false, true, false, false, false, false, false, false),
        Arguments.of(true, false, true, true, false, false, false, false, false, false),
        Arguments.of(true, false, false, true, false, false, false, false, false, false),
        Arguments.of(true, false, true, false, false, false, false, false, false, false),
        Arguments.of(true, false, false, false, false, false, false, false, false, false),
        Arguments.of(true, false, true, false, false, false, false, false, false, false),
        Arguments.of(true, false, false, false, false, false, false, false, false, false),
        // if nexus.loadAsOss doesn't have a value
        Arguments.of(false, false, false, true, true, false, false, false, true, false),
        // proMarker is present then is_oss = false
        Arguments.of(false, false, true, true, true, false, false, false, false, false),
        Arguments.of(false, false, true, true, true, false, false, false, false, false),
        Arguments.of(false, false, true, false, true, false, false, false, false, false),
        Arguments.of(false, false, true, false, true, false, false, false, false, false),
        Arguments.of(false, false, true, true, false, false, false, false, false, false),
        Arguments.of(false, false, true, true, false, false, false, false, false, false),
        Arguments.of(false, false, true, false, false, false, false, false, false, false),
        Arguments.of(false, false, true, false, false, false, false, false, false, false),
        // if clustered then is_oss = false
        Arguments.of(false, false, false, false, true, false, false, false, false, false),
        Arguments.of(false, false, false, true, false, false, false, false, false, false),
        Arguments.of(false, false, false, false, false, false, false, false, false, false),
        // if nexus.licenseFile is not null then is_oss = false
        Arguments.of(false, false, false, false, true, false, false, false, false, false),
        Arguments.of(false, false, false, false, false, false, false, false, false, false),
        // if there is a license stored in javaprefs then is_oss = false
        Arguments.of(false, false, false, true, false, false, false, false, false, false)
    );
  }

  @ParameterizedTest
  @MethodSource("provideTestCases")
  public void testProShouldSwitchToOss(
      final Boolean hasLoadAsOss,
      final Boolean loadAsOss,
      final Boolean proMarkerExists,
      final Boolean nullFileLic,
      final Boolean nullPrefLic,
      final Boolean hasLoadAsStarter,
      final Boolean loadAsStarter,
      final Boolean proStarterMarkerExists,
      final Boolean is_oss,
      final Boolean is_starter) {
    when(workDirPath.resolve("edition_pro")).thenReturn(proPath);
    when(proPath.toFile()).thenReturn(proEditionMarker);
    when(proEditionMarker.exists()).thenReturn(proMarkerExists);
    when(underTest.hasNexusLoadAs(NEXUS_LOAD_AS_OSS_PROP_NAME)).thenReturn(hasLoadAsOss);
    when(underTest.isNexusLoadAs(NEXUS_LOAD_AS_OSS_PROP_NAME)).thenReturn(loadAsOss);
    when(underTest.isNullNexusLicenseFile()).thenReturn(nullFileLic);
    when(underTest.isNullJavaPrefLicensePath(PRO_LICENSE_LOCATION)).thenReturn(nullPrefLic);

    assertEquals(is_oss, underTest.shouldSwitchToFree(workDirPath));
  }
}
