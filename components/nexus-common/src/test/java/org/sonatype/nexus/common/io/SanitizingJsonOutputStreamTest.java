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
package org.sonatype.nexus.common.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UT for {@link SanitizingJsonOutputStream}.
 *
 * @since 3.0
 */
@ExtendWith(MockitoExtension.class)
public class SanitizingJsonOutputStreamTest
    extends TestSupport
{
  private static final List<String> FIELDS = Arrays.asList("A", "B", "C", "D", "E", "F", "G", "K", "M");

  private static final String REPLACEMENT = "*";

  /**
   * Tests that a sanitizer correctly sanitizes basic content based on field names.
   */
  @Test
  public void testSanitizeContent() throws IOException {
    String input = Resources.toString(Resources.getResource(getClass(), "input.json"), StandardCharsets.UTF_8);
    String output = Resources.toString(Resources.getResource(getClass(), "output.json"), StandardCharsets.UTF_8);

    ByteArrayInputStream is = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (SanitizingJsonOutputStream stream = new SanitizingJsonOutputStream(os, FIELDS, REPLACEMENT)) {
      ByteStreams.copy(is, stream);
    }

    assertEquals(output, os.toString(StandardCharsets.UTF_8.name()));
  }
}