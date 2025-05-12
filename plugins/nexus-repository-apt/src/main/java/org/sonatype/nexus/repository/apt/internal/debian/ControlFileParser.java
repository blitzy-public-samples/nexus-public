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
package org.sonatype.nexus.repository.apt.internal.debian;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.sonatype.nexus.repository.apt.internal.debian.ControlFile.ControlField;
import org.sonatype.nexus.repository.apt.internal.debian.ControlFile.Paragraph;

/**
 * Parser for Debian control files.
 * 
 * @since 3.17
 * @see <a href="https://www.debian.org/doc/debian-policy/ch-controlfields.html">Debian Policy Manual - Control files</a>
 */
public class ControlFileParser
{
  private static final Pattern FIELD_PATTERN = Pattern.compile("((?:[\\!-9]|[\\;-\\~])+):(.*)");

  private final List<Paragraph> paragraphs = new ArrayList<>();

  private final List<ControlField> fields = new ArrayList<>();

  private final StringBuilder valueBuilder = new StringBuilder();

  private final StringBuilder sigBuilder = new StringBuilder();

  private boolean inField = false;

  private String fieldName;

  /**
   * Parses a Debian control file from the given input stream.
   * 
   * @param stream the input stream containing the control file content
   * @return the parsed control file
   * @throws IOException if an I/O error occurs during parsing
   */
  public ControlFile parseControlFile(final InputStream stream) throws IOException {
    // Clear state before parsing
    paragraphs.clear();
    fields.clear();
    valueBuilder.setLength(0);
    sigBuilder.setLength(0);
    inField = false;

    // Using StandardCharsets.UTF_8 instead of Guava's Charsets.UTF_8 for Java 21 compatibility
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        processLine(line);
      }
    }

    // Finish any pending field and paragraph
    finishField();
    finishParagraph();

    return new ControlFile(paragraphs);
  }

  /**
   * Processes a single line from the control file.
   * 
   * @param line the line to process
   * @throws IOException if an error occurs during processing
   */
  private void processLine(final String line) throws IOException {
    // Empty line indicates end of paragraph
    if (line.trim().isEmpty()) {
      finishField();
      finishParagraph();
      return;
    }

    // Using Java 21 pattern matching for switch to handle line processing
    switch (line) {
      // Skip comment lines starting with #
      case String s when s.startsWith("#") -> { /* Skip comment lines */ }
      
      // Handle continuation lines (starting with whitespace)
      case String s when Character.isWhitespace(s.codePointAt(0)) -> {
        valueBuilder.append('\n');
        valueBuilder.append(s);
      }
      
      // Handle new field lines
      default -> {
        finishField();
        beginField(line);
      }
    }
  }

  /**
   * Finishes the current paragraph by adding it to the list of paragraphs.
   * Does nothing if there are no fields in the current paragraph.
   */
  private void finishParagraph() {
    if (fields.isEmpty()) {
      return;
    }
    paragraphs.add(new Paragraph(fields));
    fields.clear();
  }

  /**
   * Finishes the current field by adding it to the list of fields.
   * Does nothing if not currently in a field.
   */
  private void finishField() {
    if (!inField) {
      return;
    }
    fields.add(new ControlField(fieldName, valueBuilder.toString()));
    valueBuilder.setLength(0);
    inField = false;
  }

  /**
   * Begins a new field by parsing the field name and initial value.
   * 
   * @param line the line containing the field
   * @throws IOException if the line is not a valid field
   */
  private void beginField(final String line) throws IOException {
    Matcher m = FIELD_PATTERN.matcher(line);
    if (!m.matches()) {
      throw new IOException("Invalid line: " + line);
    }
    fieldName = m.group(1);
    valueBuilder.append(m.group(2).trim());
    inField = true;
  }
}