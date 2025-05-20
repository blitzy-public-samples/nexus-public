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
package org.sonatype.nexus.repository.mime;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.io.InputStreamSupplier;
import org.sonatype.nexus.mime.MimeRulesSource;
import org.sonatype.nexus.mime.internal.DefaultMimeSupport;
import org.sonatype.nexus.repository.InvalidContentException;
import org.sonatype.nexus.repository.view.ContentTypes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
public class DefaultContentValidatorTest
    extends TestSupport
{
  private DefaultContentValidator testSubject;

  private final byte[] emptyZip = {80, 75, 5, 6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

  @BeforeEach
  public void setUp() {
    testSubject = new DefaultContentValidator(new DefaultMimeSupport());
  }

  /**
   * Creates an InputStreamSupplier that properly handles virtual threads.
   * This implementation ensures that the ByteArrayInputStream is created
   * fresh for each invocation, avoiding thread pinning issues when used
   * with virtual threads.
   */
  private InputStreamSupplier supplier(byte[] bytes) {
    return () -> {
      // Create a new ByteArrayInputStream for each call to avoid thread pinning
      // and ensure proper resource cleanup when used with virtual threads
      return new ByteArrayInputStream(bytes.clone());
    };
  }

  @Test
  public void simpleTextNonStrictWithDeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.txt",
        ContentTypes.TEXT_PLAIN);
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void simpleTextNonStrictWithUndeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.txt",
        null);
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void simpleTextNonStrictWithWrongDeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.txt",
        "application/zip");
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void simpleTextStrictWithWrongDeclared() throws IOException {
    String type = testSubject.determineContentType(
        true,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.txt",
        "application/zip");
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void simpleZipNonStrictWithUndeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier(emptyZip),
        MimeRulesSource.NOOP,
        "test.zip",
        null);
    assertEquals("application/zip", type);
  }

  @Test
  public void simpleZipNonStrictWithDeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier(emptyZip),
        MimeRulesSource.NOOP,
        "test.zip",
        "application/zip");
    assertEquals("application/zip", type);
  }

  @Test
  public void simpleZipStrictWithDeclared() throws IOException {
    String type = testSubject.determineContentType(
        true,
        supplier(emptyZip),
        MimeRulesSource.NOOP,
        "test.zip",
        "application/zip");
    assertEquals("application/zip", type);
  }

  @Test
  public void simpleZipStrictWithWrongDeclared() throws IOException {
    String type = testSubject.determineContentType(
        true,
        supplier(emptyZip),
        MimeRulesSource.NOOP,
        "test.zip",
        ContentTypes.TEXT_PLAIN);
    assertEquals("application/zip", type);
  }

  @Test
  public void strictWrongZipContentAsText() {
    assertThrows(InvalidContentException.class, () -> {
      testSubject.determineContentType(
          true,
          supplier(emptyZip),
          MimeRulesSource.NOOP,
          "test.txt",
          ContentTypes.TEXT_PLAIN);
    });
  }

  @Test
  public void strictWrongTextContentAsZip() {
    assertThrows(InvalidContentException.class, () -> {
      testSubject.determineContentType(
          true,
          supplier("simple text".getBytes()),
          MimeRulesSource.NOOP,
          "test.zip",
          "application/zip");
    });
  }

  @Test
  public void nonStrictWrongZipContentAsText() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier(emptyZip),
        MimeRulesSource.NOOP,
        "test.txt",
        ContentTypes.TEXT_PLAIN);
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void nonStrictWrongTextContentAsZip() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.zip",
        "application/zip");
    assertEquals("application/zip", type);
  }

  @Test
  public void strictWrongZipContentAsTextUndeclared() {
    assertThrows(InvalidContentException.class, () -> {
      testSubject.determineContentType(
          true,
          supplier(emptyZip),
          MimeRulesSource.NOOP,
          "test.txt",
          null);
    });
  }

  @Test
  public void strictWrongTextContentAsZipUndeclared() {
    assertThrows(InvalidContentException.class, () -> {
      testSubject.determineContentType(
          true,
          supplier("simple text".getBytes()),
          MimeRulesSource.NOOP,
          "test.zip",
          null);
    });
  }

  @Test
  public void nonStrictWrongZipContentAsTextUndeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier(emptyZip),
        MimeRulesSource.NOOP,
        "test.txt",
        null);
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void nonStrictWrongTextContentAsZipUndeclared() throws IOException {
    String type = testSubject.determineContentType(
        false,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.zip",
        null);
    assertEquals("application/zip", type);
  }

  @Test
  public void declaredCharsetMissing() throws IOException {
    String type = testSubject.determineContentType(
        true,
        supplier("simple text".getBytes()),
        MimeRulesSource.NOOP,
        "test.txt",
        ContentTypes.TEXT_PLAIN + "; charset=");
    assertEquals(ContentTypes.TEXT_PLAIN, type);
  }

  @Test
  public void completelyInvalid() {
    assertThrows(InvalidContentException.class, () -> {
      testSubject.determineContentType(
          true,
          supplier("simple text".getBytes()),
          MimeRulesSource.NOOP,
          "test.txt",
          "@#$*(#&%$*(%)k;lasj;klfjsdfas");
    }, STR."Invalid content type: @#$*(#&%$*(%)k;lasj;klfjsdfas");
  }

  @Test
  public void strictMavenPomHavingContentBodyWithNoXmlDeclarationAndContainingTheTextHtml() throws IOException {
    String content = """
        <project xmlns="http://maven.apache.org/POM/4.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
          <properties>
            <htmlunit.version>2.4</htmlunit.version>
          </properties>
        </project>
        """;

    String type = testSubject.determineContentType(
        true,
        supplier(content.getBytes()),
        MimeRulesSource.NOOP,
        "org/jboss/weld/weld-core-parent/1.1.12.Final/weld-core-parent-1.1.12.Final.pom.xml",
        "text/xml");
    assertEquals(ContentTypes.APPLICATION_XML, type);
  }

  @Test
  public void binaryContent() throws IOException {
    byte[] binaryFile = {1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    String type = testSubject.determineContentType(
        false,
        supplier(binaryFile),
        MimeRulesSource.NOOP,
        "vim",
        null);
    assertEquals(ContentTypes.APPLICATION_OCTET_STREAM, type);
  }

  @Test
  public void binaryContentStrict() {
    byte[] binaryFile = {1, 2, 3, 4, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};

    assertThrows(InvalidContentException.class, () -> {
      testSubject.determineContentType(
          true,
          supplier(binaryFile),
          MimeRulesSource.NOOP,
          "vim",
          null);
    }, STR."Unable to determine content type for: vim");
  }
}