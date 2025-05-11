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
import org.junit.jupiter.api.DisplayName;

// Import for Java 21 preview features
import static java.lang.StringTemplate.STR;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ApplicationVersionSupport}.
 * Updated for Java 21 compatibility with JUnit Jupiter.
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
   * Verify that edition returns non-UNKNOWN value.
   */
  @Test
  @DisplayName("Edition should return TEST value")
  public void testGetEdition() {
    assertThat(underTest.getEdition(), is("TEST"));
  }

  /**
   * Verify that version returns the configured value.
   */
  @Test
  @DisplayName("Version should return configured value")
  public void testGetVersion() {
    props.setProperty(ApplicationVersionSupport.VERSION, "123");
    assertThat(underTest.getVersion(), is("123"));
  }

  /**
   * Verify that version returns UNKNOWN when not configured.
   */
  @Test
  @DisplayName("Version should return UNKNOWN when not configured")
  public void testGetVersion_missing() {
    assertThat(underTest.getVersion(), is(ApplicationVersionSupport.UNKNOWN));
  }
  
  /**
   * Test Java 21 String Templates feature with version and edition formatting.
   * This test demonstrates the use of Java 21's String Templates feature
   * to create formatted strings with embedded expressions.
   */
  @Test
  @DisplayName("Test version and edition formatting with Java 21 String Templates")
  public void testVersionEditionFormatting() {
    // Set up a version for testing
    props.setProperty(ApplicationVersionSupport.VERSION, "21.0.1");
    
    // Create a formatted string using Java 21 String Templates
    String formattedInfo = STR."Application: \{underTest.getEdition()} version \{underTest.getVersion()}";
    
    // Verify the formatted string
    assertThat(formattedInfo, is("Application: TEST version 21.0.1"));
  }
}