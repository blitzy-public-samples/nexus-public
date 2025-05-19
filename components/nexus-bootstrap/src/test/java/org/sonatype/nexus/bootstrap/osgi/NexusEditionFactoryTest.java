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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class NexusEditionFactoryTest
{
  @Mock
  private NexusEdition mockEditionPro;

  @Mock
  private NexusEdition mockEditionCE;

  @Mock
  private Path mockWorkDirPath;

  private Properties properties;

  @BeforeEach
  public void setUp() {
    properties = new Properties();
  }

  @Test
  public void testSelectActiveEdition_selectsCorrectEdition() {
    when(mockEditionPro.applies(properties, mockWorkDirPath)).thenReturn(false);
    when(mockEditionCE.applies(properties, mockWorkDirPath)).thenReturn(true);

    NexusEdition result = NexusEditionFactory.findActiveEdition(Arrays.asList(mockEditionPro, mockEditionCE), properties, mockWorkDirPath);

    assertTrue(result == mockEditionCE);
  }

  @Test
  public void testSelectActiveEdition_selectsOssEditionIfNoneApply() {
    when(mockEditionPro.applies(properties, mockWorkDirPath)).thenReturn(false);
    when(mockEditionCE.applies(properties, mockWorkDirPath)).thenReturn(false);

    NexusEdition result = NexusEditionFactory.findActiveEdition(Arrays.asList(mockEditionPro, mockEditionCE), properties, mockWorkDirPath);

    assertTrue(result instanceof OpenCoreNexusEdition);
  }
}
