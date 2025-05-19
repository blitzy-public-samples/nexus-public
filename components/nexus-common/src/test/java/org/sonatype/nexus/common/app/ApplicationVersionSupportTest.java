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
package org.sonatype.nexus.common.app;

import java.util.Properties;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ApplicationVersionSupport}.
 */
public class ApplicationVersionSupportTest
    extends TestSupport
{
  private ApplicationVersionSupport underTest;

  private Properties props;

  @BeforeEach
  public void setUp() {
    props = new Properties();
    underTest = new ApplicationVersionSupport()
    {
      @Override
      public Properties getProperties() {
        return props;
      }

      @Override
      public String getEdition() {
        return "TEST";
      }
    };
  }

  /**
   * Verify that the custom edition is returned correctly.
   */
  @Test
  public void shouldReturnCustomEdition() {
    assertEquals("TEST", underTest.getEdition());
  }

  /**
   * Verify that the version is retrieved from properties when available.
   */
  @Test
  public void shouldReturnVersionFromProperties() {
    props.setProperty(ApplicationVersionSupport.VERSION, "123");
    assertEquals("123", underTest.getVersion());
  }

  /**
   * Verify that UNKNOWN is returned when version property is missing.
   */
  @Test
  public void shouldReturnUnknownWhenVersionMissing() {
    assertEquals(ApplicationVersionSupport.UNKNOWN, underTest.getVersion());
  }
}