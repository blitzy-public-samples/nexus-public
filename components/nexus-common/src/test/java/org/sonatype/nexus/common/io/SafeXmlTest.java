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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.StringJoiner;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES;
import static javax.xml.stream.XMLInputFactory.SUPPORT_DTD;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

class SafeXmlTest
{
  @TempDir
  static Path tempDir;

  private static File xmlDocument;

  @BeforeAll
  static void setup() throws IOException {
    xmlDocument = tempDir.resolve("test.xml").toFile();
    File externalFile = tempDir.resolve("external.txt").toFile();

    Files.write(externalFile.toPath(), Collections.singleton("bar"), StandardOpenOption.CREATE);

    try (InputStream content = SafeXmlTest.class.getResourceAsStream("xxe.xml")) {
      StringJoiner joiner = new StringJoiner(System.lineSeparator());
      IOUtils.readLines(content).forEach(joiner::add);

      String output = joiner.toString().replace("${fileUri}", externalFile.toURI().toString());
      Files.copy(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), xmlDocument.toPath(),
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Test
  void testDocumentBuilderFactory() throws IOException, SAXException, ParserConfigurationException {
    DocumentBuilder builder = SafeXml.newdocumentBuilderFactory().newDocumentBuilder();
    Document doc = builder.parse(xmlDocument);

    assertThat(doc.getElementsByTagName("foo").item(0).getTextContent(), not(containsString("bar")));
  }

  @Test
  void testStrictDocumentBuilderFactory() {
    SAXParseException exception = assertThrows(SAXParseException.class, () -> {
      DocumentBuilder builder = SafeXml.newStrictDocumentBuilderFactory().newDocumentBuilder();
      builder.parse(xmlDocument);
    });
    
    assertThat(exception.getMessage(), containsString("DOCTYPE is disallowed"));
  }

  @Test
  void testTransformerFactory() {
    TransformerException exception = assertThrows(TransformerException.class, () -> {
      StreamSource xmlSource = new StreamSource(xmlDocument);
      Transformer transformer = SafeXml.newTransformerFactory().newTransformer();
      transformer.transform(xmlSource, new StreamResult(new StringWriter()));
    });
    
    assertThat(exception.getMessage(), containsString("accessExternalDTD"));
  }

  @Test
  void testSAXParserFactory() throws ParserConfigurationException, SAXException, IOException {
    StringBuilder sb = new StringBuilder();

    DefaultHandler handler = new DefaultHandler()
    {
      @Override
      public void characters(final char[] ch, final int start, final int length) {
        sb.append(ch);
      }
    };
    SafeXml.newSaxParserFactory().newSAXParser().parse(xmlDocument, handler);
    assertThat(sb.toString(), not(containsString("bar")));
  }

  @Test
  void testStrictSAXParserFactory() {
    SAXParseException exception = assertThrows(SAXParseException.class, () -> {
      SafeXml.newStrictSaxParserFactory().newSAXParser().parse(xmlDocument, new DefaultHandler());
    });
    
    assertThat(exception.getMessage(), containsString("DOCTYPE is disallowed"));
  }

  @Test
  void testSaxTransformerFactory() {
    TransformerException exception = assertThrows(TransformerException.class, () -> {
      StreamSource xmlSource = new StreamSource(xmlDocument);
      Transformer transformer = SafeXml.newSaxTransformerFactory().newTransformer();
      transformer.transform(xmlSource, new StreamResult(new StringWriter()));
    });
    
    assertThat(exception.getMessage(), containsString("accessExternalDTD"));
  }

  @Test
  void testXmlInputFactory() {
    XMLStreamException exception = assertThrows(XMLStreamException.class, () -> {
      // This code is more complicated than it needs to be to demonstrate finding the XXE content.
      try (Reader reader = Files.newBufferedReader(xmlDocument.toPath())) {
        XMLEventReader xmlEventReader = SafeXml.newXmlInputFactory().createXMLEventReader(reader);

        String content = "";
        boolean track = false;
        while (xmlEventReader.hasNext()) {
          XMLEvent xmlEvent = xmlEventReader.nextEvent();

          if (xmlEvent.isStartElement() && xmlEvent.asStartElement().getName().getLocalPart().equals("foo")) {
            track = true;
          }
          else if (xmlEvent.isEndElement() && xmlEvent.asEndElement().getName().getLocalPart().equals("foo")) {
            assertThat(content, not(containsString("bar")));
            return;
          }
          else if (track && xmlEvent.isCharacters()) {
            content += xmlEvent.asCharacters().getData();
          }
        }
        fail("Did not find element foo");
      }
    });
    
    assertThat(exception.getMessage(), containsString("The entity \"xxe\" was referenced, but not declared."));
  }

  @Test
  void shouldNotSupportDocTypeDefinitions() {
    XMLInputFactory xmlInputFactory = SafeXml.newXmlInputFactory();

    Object supportDtd = xmlInputFactory.getProperty(SUPPORT_DTD);

    assertFalse(Boolean.parseBoolean(supportDtd.toString()));
  }

  @Test
  void shouldNotSupportExternalEntities() {
    XMLInputFactory xmlInputFactory = SafeXml.newXmlInputFactory();

    Object supportExternalEntities = xmlInputFactory.getProperty(IS_SUPPORTING_EXTERNAL_ENTITIES);

    assertFalse(Boolean.parseBoolean(supportExternalEntities.toString()));
  }
}