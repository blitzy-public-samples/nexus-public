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
package org.sonatype.nexus.supportzip;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.sonatype.nexus.common.io.SafeXml;

import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * {@link FileContentSourceSupport} that allows XSLT rewrites of existing XML content. Note that the transform
 * is performed in memory and the results saved for the lifetime of the instance, so use on large XML files is not
 * recommended.
 *
 * @since 3.0
 */
public class SanitizedXmlSourceSupport
    extends FileContentSourceSupport
{
  private final String stylesheet;

  private byte[] content;

  /**
   * Constructor.
   */
  public SanitizedXmlSourceSupport(final Type type,
                                   final String path,
                                   final File file,
                                   final Priority priority,
                                   final String stylesheet)
  {
    super(type, path, file, priority);
    this.stylesheet = checkNotNull(stylesheet);
  }

  /**
   * Prepares the content by applying the XSLT transformation to the XML file.
   * Uses a Virtual Thread to perform the transformation asynchronously, which improves
   * performance for I/O-bound operations like XML processing.
   *
   * @throws Exception if an error occurs during preparation or transformation
   * @since 3.0
   */
  @Override
  public void prepare() throws Exception {
    super.prepare();
    checkState(content == null);
    
    // Create a CompletableFuture to hold the result of the transformation
    CompletableFuture<byte[]> future = new CompletableFuture<>();
    
    // Start a virtual thread to perform the XML transformation
    // Virtual threads are lightweight and managed by the JVM, making them ideal for I/O operations
    Thread.startVirtualThread(() -> {
      try {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
          try (OutputStream output = new BufferedOutputStream(stream)) {
            // Set up the XSLT transformation
            StreamSource styleSource = new StreamSource(new StringReader(stylesheet));
            TransformerFactory transformerFactory = SafeXml.newTransformerFactory();
            Transformer transformer = transformerFactory.newTransformer(styleSource);

            SAXParserFactory parserFactory = SafeXml.newSaxParserFactory();
            parserFactory.setNamespaceAware(true);

            SAXParser parser = parserFactory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            XMLReader reader = parser.getXMLReader();
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

            // Perform the transformation
            transformer.transform(new SAXSource(reader, new InputSource(input)), new StreamResult(output));
          }
        }
        // Complete the future with the transformation result
        future.complete(stream.toByteArray());
      } catch (Exception e) {
        // Complete the future exceptionally if an error occurs
        future.completeExceptionally(e);
        log.debug("Error during XML transformation in virtual thread: {}", e.getMessage());
      }
    });
    
    try {
      // Wait for the transformation to complete and get the result
      content = future.get();
      log.debug("XML transformation completed successfully for: {}", file);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("XML transformation was interrupted", e);
    } catch (ExecutionException e) {
      throw new RuntimeException("Error during XML transformation: " + e.getCause().getMessage(), e.getCause());
    }
  }

  @Override
  public long getSize() {
    checkState(content != null);
    return content.length;
  }

  @Override
  public InputStream getContent() throws Exception {
    checkState(content != null);
    log.debug("Reading: {} from memory", file);
    return new BufferedInputStream(new ByteArrayInputStream(content));
  }