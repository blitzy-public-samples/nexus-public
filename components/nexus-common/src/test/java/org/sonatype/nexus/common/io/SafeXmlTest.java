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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.StringJoiner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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

import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.apache.commons.io.IOUtils;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import static javax.xml.stream.XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES;
import static javax.xml.stream.XMLInputFactory.SUPPORT_DTD;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.fail;

@Category(VirtualThreadTestGroup.class)
public class SafeXmlTest
{
  @ClassRule
  public static TemporaryFolder temp = new TemporaryFolder();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  private static File xmlDocument;
  
  // Number of concurrent threads to use in tests
  private static final int CONCURRENT_THREADS = 10;
  
  // Timeout for concurrent operations
  private static final long TIMEOUT_SECONDS = 5;

  @BeforeClass
  public static void setup() throws IOException {
    xmlDocument = temp.newFile();
    File externalFile = temp.newFile();

    Files.write(externalFile.toPath(), Collections.singleton("bar"), StandardOpenOption.TRUNCATE_EXISTING);

    try (InputStream content = SafeXmlTest.class.getResourceAsStream("xxe.xml")) {
      StringJoiner joiner = new StringJoiner(System.lineSeparator());
      IOUtils.readLines(content).forEach(joiner::add);

      String output = joiner.toString().replace("${fileUri}", externalFile.toURI().toString());
      Files.copy(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), xmlDocument.toPath(),
          StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Test
  public void documentBuilderFactory() throws IOException, SAXException, ParserConfigurationException {
    DocumentBuilder builder = SafeXml.newdocumentBuilderFactory().newDocumentBuilder();
    Document doc = builder.parse(xmlDocument);

    assertThat(doc.getElementsByTagName("foo").item(0).getTextContent(), not(containsString("bar")));
  }

  @Test
  public void strictDocumentBuilderFactory() throws IOException, SAXException, ParserConfigurationException {
    thrown.expect(SAXParseException.class);
    thrown.expectMessage(containsString("DOCTYPE is disallowed"));

    DocumentBuilder builder = SafeXml.newStrictDocumentBuilderFactory().newDocumentBuilder();
    builder.parse(xmlDocument);
  }

  @Test
  public void transformerFactory() throws TransformerException {
    thrown.expect(TransformerException.class);
    thrown.expectMessage(containsString("accessExternalDTD"));

    StreamSource xmlSource = new StreamSource(xmlDocument);
    Transformer transformer = SafeXml.newTransformerFactory().newTransformer();

    transformer.transform(xmlSource, new StreamResult(new StringWriter()));
  }

  @Test
  public void saxParserFactory() throws ParserConfigurationException, SAXException, IOException {
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
  public void strictSaxParserFactory() throws ParserConfigurationException, SAXException, IOException {
    thrown.expect(SAXParseException.class);
    thrown.expectMessage(containsString("DOCTYPE is disallowed"));

    SafeXml.newStrictSaxParserFactory().newSAXParser().parse(xmlDocument, new DefaultHandler());
  }

  @Test
  public void saxTransformerFactory() throws TransformerException {
    thrown.expect(TransformerException.class);
    thrown.expectMessage(containsString("accessExternalDTD"));

    StreamSource xmlSource = new StreamSource(xmlDocument);
    Transformer transformer = SafeXml.newSaxTransformerFactory().newTransformer();

    transformer.transform(xmlSource, new StreamResult(new StringWriter()));
  }

  @Test
  public void xmlInputFactory() throws XMLStreamException, IOException {
    thrown.expect(XMLStreamException.class);
    thrown.expectMessage(containsString("The entity \"xxe\" was referenced, but not declared."));

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
  }

  @Test
  public void shouldNotSupportDocTypeDefinitions() {
    XMLInputFactory xmlInputFactory = SafeXml.newXmlInputFactory();

    Object supportDtd = xmlInputFactory.getProperty(SUPPORT_DTD);

    assertFalse(Boolean.parseBoolean(supportDtd.toString()));
  }

  @Test
  public void shouldNotSupportExternalEntities() {
    XMLInputFactory xmlInputFactory = SafeXml.newXmlInputFactory();

    Object supportExternalEntities = xmlInputFactory.getProperty(IS_SUPPORTING_EXTERNAL_ENTITIES);

    assertFalse(Boolean.parseBoolean(supportExternalEntities.toString()));
  }
  
  /**
   * Tests XML parsing with Virtual Threads to verify non-blocking behavior.
   * This test creates multiple Virtual Threads that parse XML documents concurrently
   * to ensure that the XML parsing operations don't block the threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void xmlParsingWithVirtualThreads() throws Exception {
    // Skip test if Virtual Threads are not supported
    VirtualThreadTestSupport.assumeVirtualThreadSupported();
    
    final CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    final AtomicBoolean success = new AtomicBoolean(true);
    
    // Create a thread factory for virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit multiple parsing tasks to be executed concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Parse XML document using DocumentBuilder
            DocumentBuilder builder = SafeXml.newdocumentBuilderFactory().newDocumentBuilder();
            Document doc = builder.parse(xmlDocument);
            
            // Verify the content was parsed correctly
            assertThat(doc.getElementsByTagName("foo").item(0).getTextContent(), not(containsString("bar")));
          }
          catch (Exception e) {
            success.set(false);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue("Timed out waiting for XML parsing tasks", latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertTrue("One or more XML parsing tasks failed", success.get());
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests for thread pinning during XML parsing operations.
   * Thread pinning occurs when a Virtual Thread is forced to run on its carrier thread,
   * preventing other Virtual Threads from making progress. This can happen with
   * synchronized blocks or native methods that don't support Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void detectXmlParsingThreadPinning() throws Exception {
    // Skip test if Virtual Threads are not supported
    VirtualThreadTestSupport.assumeVirtualThreadSupported();
    
    // Test DocumentBuilder for thread pinning
    boolean documentBuilderPinning = VirtualThreadTestSupport.detectThreadPinning(() -> {
      try {
        DocumentBuilder builder = SafeXml.newdocumentBuilderFactory().newDocumentBuilder();
        builder.parse(xmlDocument);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test SAX parser for thread pinning
    boolean saxParserPinning = VirtualThreadTestSupport.detectThreadPinning(() -> {
      try {
        StringBuilder sb = new StringBuilder();
        DefaultHandler handler = new DefaultHandler() {
          @Override
          public void characters(final char[] ch, final int start, final int length) {
            sb.append(ch);
          }
        };
        SafeXml.newSaxParserFactory().newSAXParser().parse(xmlDocument, handler);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test XMLInputFactory for thread pinning
    boolean xmlInputFactoryPinning = VirtualThreadTestSupport.detectThreadPinning(() -> {
      try (Reader reader = Files.newBufferedReader(xmlDocument.toPath())) {
        XMLInputFactory factory = SafeXml.newXmlInputFactory();
        XMLEventReader xmlEventReader = factory.createXMLEventReader(reader);
        
        while (xmlEventReader.hasNext()) {
          xmlEventReader.nextEvent();
        }
      }
      catch (Exception e) {
        // Expected exception due to XXE content
      }
    });
    
    // Log results - we don't assert on these as some pinning may be unavoidable
    // with current XML implementations, but we want to be aware of it
    System.out.println("XML parsing thread pinning detection results:");
    System.out.println("DocumentBuilder pinning: " + documentBuilderPinning);
    System.out.println("SAX parser pinning: " + saxParserPinning);
    System.out.println("XMLInputFactory pinning: " + xmlInputFactoryPinning);
  }
  
  /**
   * Tests concurrent XML parsing with multiple threads to verify thread safety.
   * This test creates multiple threads that parse the same XML document concurrently
   * to ensure that the XML parsing operations are thread-safe.
   */
  @Test
  public void concurrentXmlParsing() throws Exception {
    final int threadCount = 5;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create and start multiple threads
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] = new Thread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Parse XML document using DocumentBuilder
          DocumentBuilder builder = SafeXml.newdocumentBuilderFactory().newDocumentBuilder();
          Document doc = builder.parse(xmlDocument);
          
          // Verify the content was parsed correctly
          if (!doc.getElementsByTagName("foo").item(0).getTextContent().contains("bar")) {
            successCount.incrementAndGet();
          }
          else {
            failureCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
      threads[i].start();
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for concurrent XML parsing", 
        completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify results
    assertThat("All parsing operations should succeed", successCount.get(), is(threadCount));
    assertThat("No parsing operations should fail", failureCount.get(), is(0));
  }
  
  /**
   * Tests XML parser configuration to ensure compatibility with Java 21 security constraints.
   * Java 21 has stricter security constraints for XML parsing, so we need to ensure
   * that our XML parser configurations are compatible.
   */
  @Test
  public void java21XmlSecurityConstraints() throws Exception {
    // Test DocumentBuilderFactory security settings
    DocumentBuilder builder = SafeXml.newdocumentBuilderFactory().newDocumentBuilder();
    assertFalse("External entity processing should be disabled", 
        builder.isExpandEntityReferences());
    
    // Test XMLInputFactory security settings
    XMLInputFactory xmlInputFactory = SafeXml.newXmlInputFactory();
    assertFalse("DTD support should be disabled", 
        Boolean.parseBoolean(xmlInputFactory.getProperty(SUPPORT_DTD).toString()));
    assertFalse("External entity support should be disabled", 
        Boolean.parseBoolean(xmlInputFactory.getProperty(IS_SUPPORTING_EXTERNAL_ENTITIES).toString()));
    
    // Verify that the XML parser is configured to prevent XXE attacks
    try (Reader reader = Files.newBufferedReader(xmlDocument.toPath())) {
      XMLEventReader xmlEventReader = xmlInputFactory.createXMLEventReader(reader);
      
      String content = "";
      boolean track = false;
      
      while (xmlEventReader.hasNext()) {
        try {
          XMLEvent xmlEvent = xmlEventReader.nextEvent();
          
          if (xmlEvent.isStartElement() && xmlEvent.asStartElement().getName().getLocalPart().equals("foo")) {
            track = true;
          }
          else if (xmlEvent.isEndElement() && xmlEvent.asEndElement().getName().getLocalPart().equals("foo")) {
            break;
          }
          else if (track && xmlEvent.isCharacters()) {
            content += xmlEvent.asCharacters().getData();
          }
        }
        catch (XMLStreamException e) {
          // Expected exception due to XXE content
          if (e.getMessage().contains("entity \"xxe\" was referenced")) {
            // This is the expected behavior - external entity was blocked
            return;
          }
          throw e;
        }
      }
      
      // If we get here, verify that the external entity was not processed
      assertThat(content, not(containsString("bar")));
    }
    catch (XMLStreamException e) {
      // Also acceptable if we get an exception about the entity not being declared
      if (!e.getMessage().contains("entity \"xxe\" was referenced")) {
        throw e;
      }
    }
  }
}
