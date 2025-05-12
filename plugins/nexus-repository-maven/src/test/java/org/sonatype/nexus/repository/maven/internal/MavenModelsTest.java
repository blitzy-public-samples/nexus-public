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
package org.sonatype.nexus.repository.maven.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.StringTemplate;
import java.lang.reflect.Field;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.model.Model;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link MavenModels} class.
 * Updated for Java 21 compatibility to test new features like String Templates.
 * 
 * @since 3.0
 * @see MavenModels
 */
public class MavenModelsTest
    extends TestSupport
{
  String notXml = "not xml";
  
  /**
   * Error message for testing string templates
   */
  private static final String TEST_ERROR_MESSAGE = "Test error message";

  /**
   * Tests that reading a model from an empty input stream returns null.
   */
  @Test
  public void testReadModel_emptyInputStreamIsNull() throws Exception {
    Model model = MavenModels.readModel(new ByteArrayInputStream(new byte[0]));
    assertThat(model, nullValue());
  }

  /**
   * Tests that reading a model from non-XML content returns null.
   */
  @Test
  public void testReadModel_NotXmlIsNull() throws Exception {
    Model model = MavenModels.readModel(new ByteArrayInputStream(notXml.getBytes()));
    assertThat(model, nullValue());
  }

  /**
   * Tests that reading metadata from XML without closing tags returns null.
   */
  @Test
  public void testReadModel_WithoutClosingTagsIsNull() throws Exception {
    Metadata metadata = MavenModels.readMetadata(
        getClass().getResourceAsStream("/org/sonatype/nexus/repository/maven/metadataWithoutClosingTags.xml"));
    assertThat(metadata, nullValue());
  }
  
  /**
   * Tests that the StringTemplate processor is properly initialized in MavenModels.
   * This test verifies Java 21 String Template feature usage in the class.
   */
  @Test
  public void testStringTemplateProcessor() throws Exception {
    // Access the private STR field using reflection
    Field strField = MavenModels.class.getDeclaredField("STR");
    strField.setAccessible(true);
    StringTemplate.Processor<?> processor = (StringTemplate.Processor<?>) strField.get(null);
    
    // Verify the processor is initialized
    assertThat(processor, is(notNullValue()));
    
    // Test the processor with a simple template
    String result = (String) processor.process(STR."Error message: \{TEST_ERROR_MESSAGE}");
    assertThat(result, is(equalTo("Error message: Test error message")));
  }
}