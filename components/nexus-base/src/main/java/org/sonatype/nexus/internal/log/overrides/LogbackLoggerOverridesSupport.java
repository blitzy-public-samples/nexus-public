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
package org.sonatype.nexus.internal.log.overrides;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.sonatype.goodies.common.FileReplacer;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.io.SafeXml;
import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.internal.log.LoggerOverrides;

import ch.qos.logback.classic.Logger;
import com.google.common.collect.Maps;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support logback files operations for {@link LoggerOverrides}
 */
public abstract class LogbackLoggerOverridesSupport
    extends StateGuardLifecycleSupport
{
  private static final String LOGBACK_OVERRIDES_FILENAME = "logback-overrides.xml";

  private final File logbackFile;

  protected LogbackLoggerOverridesSupport(final ApplicationDirectories applicationDirectories) {
    this(new File(checkNotNull(applicationDirectories)
        .getWorkDirectory("etc/logback"), LOGBACK_OVERRIDES_FILENAME));
  }

  protected LogbackLoggerOverridesSupport(final File logbackFile) {
    this.logbackFile = checkNotNull(logbackFile);
    log.info(STR."File: \{logbackFile}");
  }

  protected boolean logbackFileExists() {
    return logbackFile.exists();
  }

  /**
   * Read logger levels from logback.xml formatted include file.
   */
  protected Map<String, LoggerLevel> readFromFile() throws Exception {
    final Map<String, LoggerLevel> result = Maps.newHashMap();

    Future<Map<String, LoggerLevel>> future = Thread.ofVirtual().name("logback-read-thread").start(() -> {
      SAXParserFactory parserFactory = SafeXml.newSaxParserFactory();
      parserFactory.setValidating(false);
      parserFactory.setNamespaceAware(true);
      parserFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      
      SAXParser parser = parserFactory.newSAXParser();
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      parser.setProperty(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
      
      try {
        parser.parse(logbackFile, new DefaultHandler()
        {
          @Override
          public void startElement(
              final String uri,
              final String localName,
              final String qName,
              final Attributes attributes) throws SAXException
          {
            // NOTE: ATM we are ignoring 'property' elements, this is needed for root, but is only needed
            // NOTE: to persist as a property for use in top-level logback.xml file

            if ("logger".equals(localName)) {
              String name = attributes.getValue("name");
              String level = attributes.getValue("level");
              result.put(name, LoggerLevel.valueOf(level));
            }
          }
        });
      } catch (Exception e) {
        log.error(STR."Error parsing logback file: \{logbackFile}", e);
        throw e;
      }
      return result;
    });
    
    try {
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      log.error(STR."Failed to read logback overrides from file: \{logbackFile}", e);
      throw new Exception(STR."Failed to read logback overrides: \{e.getMessage()}", e);
    }
  }

  /**
   * Write logger levels and root property to logback.xml formatted include file.
   */
  protected void writeToFile(final Map<String, LoggerLevel> overrides) throws Exception {
    final FileReplacer fileReplacer = new FileReplacer(logbackFile);
    fileReplacer.setDeleteBackupFile(true);
    
    Future<?> future = Thread.ofVirtual().name("logback-write-thread").start(() -> {
      try {
        fileReplacer.replace(output -> {
          try (final BufferedWriter out = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            out.write("<?xml version='1.0' encoding='UTF-8'?>");
            out.newLine();
            out.newLine();
            out.write("<!--");
            out.newLine();
            out.write("DO NOT EDIT - Automatically generated; User-customized logging levels");
            out.newLine();
            out.write("-->");
            out.newLine();
            out.newLine();
            out.write("<included>");
            out.newLine();
            for (Entry<String, LoggerLevel> entry : overrides.entrySet()) {
              if (Logger.ROOT_LOGGER_NAME.equals(entry.getKey())) {
                out.write(STR."  <property name='root.level' value='\{entry.getValue()}'/>");
                out.newLine();
              }
              else {
                out.write(STR."  <logger name='\{entry.getKey()}' level='\{entry.getValue()}'/>");
                out.newLine();
              }
            }
            out.write("</included>");
            out.newLine();
          }
        });
      } catch (Exception e) {
        log.error(STR."Error writing to logback file: \{logbackFile}", e);
        throw e;
      }
    });
    
    try {
      future.get();
    } catch (InterruptedException | ExecutionException e) {
      log.error(STR."Failed to write logback overrides to file: \{logbackFile}", e);
      throw new Exception(STR."Failed to write logback overrides: \{e.getMessage()}", e);
    }
  }
}