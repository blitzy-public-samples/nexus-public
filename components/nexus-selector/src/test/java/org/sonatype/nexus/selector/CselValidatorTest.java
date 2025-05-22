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
package org.sonatype.nexus.selector;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.jexl3.JexlException;
import org.junit.jupiter.api.Test;

import static com.google.common.collect.Streams.stream;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sonatype.nexus.selector.CselValidator.validateCselExpression;

public class CselValidatorTest
    extends TestSupport
{
  public static final String BASEDIR = new File(System.getProperty("basedir", "")).getAbsolutePath();

  private JexlEngine engine = new JexlEngine();

  private ObjectMapper mapper = new ObjectMapper();

  @Test
  void parsesAllValidContentSelectors() throws Exception {
    URL jsonFile = this.getClass().getResource("/validJexlContentSelectors.json");
    JsonNode contentSelectors = mapper.readTree(jsonFile);
    stream(contentSelectors).map(JsonNode::asText).forEach(this::validateExpression);
  }

  @Test
  void failsToParseInvalidContentSelectors() {
    assertThrows(JexlException.Parsing.class, () -> validateExpression("invalid content selector"));
  }

  @Test
  void failsToParseCoordinateContentSelectors() {
    assertThrows(JexlException.class, () -> validateExpression("coordinate.groupId == \"com.sonatype\""));
  }

  @Test
  void failsToValidateInvalidContentSelectors() {
    assertThrows(JexlException.class, () -> validateExpression("a.b.c = false"));
  }

  @Test
  void failsToValidateEmbeddedSingleQuoteInStrings() {
    assertThrows(JexlException.class, () -> validateExpression("format == \"'\""));
  }

  @Test
  void failsToValidateEmbeddedDoubleQuoteInStrings() {
    assertThrows(JexlException.class, () -> validateExpression("format == '\"'"));
  }

  @Test
  void failsToValidateInvalidRegex() {
    assertThrows(JexlException.class, () -> validateExpression("path =~ '*foo*'"));
  }

  @Test
  void validatesPatternMatchingExpressions() {
    validateExpression("path instanceof String");
    validateExpression("format instanceof String format && format.length() > 0");
  }

  @Test
  void validatesRecordPatternMatchingExpressions() {
    validateExpression("asset instanceof AssetInfo(String path, String format) && path.startsWith('/libs/')");
    validateExpression("component instanceof ComponentInfo(String group, String name, String version) && version == '1.0.0'");
  }

  public static File resolveBaseFile(final String path) {
    return resolveBasePath(path).toFile();
  }

  public static Path resolveBasePath(final String path) {
    return Paths.get(BASEDIR, path);
  }

  private void validateExpression(final String expression) {
    validateCselExpression(engine.parseExpression(expression));
  }
}