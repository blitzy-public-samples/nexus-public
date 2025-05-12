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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Represents a Debian control file structure with paragraphs and fields.
 * 
 * @since 3.17
 * @see <a href="https://www.debian.org/doc/debian-policy/ch-controlfields.html">Debian Policy Manual - Control files</a>
 */
public class ControlFile
{
  /**
   * Builder for creating and modifying ControlFile instances.
   */
  public static class Builder
  {
    private List<Paragraph> paragraphs;

    private Builder(final List<Paragraph> paragraphs) {
      super();
      this.paragraphs = paragraphs;
    }

    /**
     * Removes paragraphs that match the given predicate.
     *
     * @param p the predicate to filter paragraphs
     * @return this builder instance
     */
    public Builder removeParagraphs(final Predicate<Paragraph> p) {
      paragraphs = paragraphs.stream().filter(p).collect(Collectors.toList());
      return this;
    }

    /**
     * Adds a paragraph to the control file.
     *
     * @param p the paragraph to add
     * @return this builder instance
     */
    public Builder addParagraph(final Paragraph p) {
      paragraphs.add(p);
      return this;
    }

    /**
     * Replaces paragraphs that match the given predicate with a new paragraph.
     *
     * @param predicate the predicate to match paragraphs for replacement
     * @param p the new paragraph to add
     * @return this builder instance
     */
    public Builder replaceParagraph(final Predicate<Paragraph> predicate, final Paragraph p) {
      paragraphs = Stream
          .concat(paragraphs.stream().filter(predicate.negate()), Stream.of(p))
          .collect(Collectors.toList());
      return this;
    }

    /**
     * Transforms paragraphs that match the given predicate using the provided transform function.
     *
     * @param predicate the predicate to match paragraphs for transformation
     * @param transform the function to transform matching paragraphs
     * @return this builder instance
     */
    public Builder transformParagraphs(final Predicate<Paragraph> predicate, final Function<Paragraph, Paragraph> transform) {
      paragraphs = paragraphs.stream()
          .map(p -> predicate.test(p) ? transform.apply(p) : p)
          .collect(Collectors.toList());
      return this;
    }

    /**
     * Builds a new ControlFile with the current state of this builder.
     *
     * @return a new ControlFile instance
     */
    public ControlFile build() {
      return new ControlFile(paragraphs);
    }
  }

  /**
   * Creates a new empty builder for constructing a ControlFile.
   *
   * @return a new builder instance
   */
  public static Builder newBuilder() {
    return new Builder(new ArrayList<>());
  }

  private final List<Paragraph> paragraphs;

  /**
   * Constructs a new ControlFile with the given paragraphs.
   *
   * @param paragraphs the list of paragraphs in this control file
   */
  public ControlFile(final List<Paragraph> paragraphs) {
    super();
    this.paragraphs = new ArrayList<>(paragraphs);
  }

  /**
   * Creates a builder initialized with the paragraphs from this control file.
   *
   * @return a new builder instance
   */
  public Builder builder() {
    return new Builder(new ArrayList<>(paragraphs));
  }

  /**
   * Gets all paragraphs in this control file.
   *
   * @return the list of paragraphs
   */
  public List<Paragraph> getParagraphs() {
    return paragraphs;
  }

  /**
   * Gets a field with the specified name from the first paragraph.
   *
   * @param name the field name to look for
   * @return an Optional containing the field if found, or empty if not found or no paragraphs exist
   */
  public Optional<ControlField> getField(final String name) {
    if (paragraphs.isEmpty()) {
      return Optional.empty();
    }

    return paragraphs.get(0).getField(name);
  }

  /**
   * Gets all fields from the first paragraph.
   *
   * @return the list of fields, or an empty list if no paragraphs exist
   */
  public List<ControlField> getFields() {
    if (paragraphs.isEmpty()) {
      return Collections.emptyList();
    }

    return paragraphs.get(0).getFields();
  }

  /**
   * Represents a paragraph in a Debian control file, which is a collection of fields.
   */
  public static class Paragraph
  {
    private final List<ControlField> fields;

    /**
     * Constructs a new Paragraph with the given fields.
     *
     * @param fields the list of fields in this paragraph
     */
    public Paragraph(final List<ControlField> fields) {
      super();
      this.fields = new ArrayList<>(fields);
    }

    /**
     * Gets a field with the specified name from this paragraph.
     *
     * @param name the field name to look for
     * @return an Optional containing the field if found, or empty if not found
     */
    public Optional<ControlField> getField(final String name) {
      return fields.stream()
          .filter(f -> f.key.equals(name))
          .findFirst();
    }

    /**
     * Gets all fields in this paragraph.
     *
     * @return an unmodifiable list of all fields
     */
    public List<ControlField> getFields() {
      return Collections.unmodifiableList(fields);
    }

    /**
     * Creates a new Paragraph with updated fields.
     * Fields with the same key in the updateFields list will replace existing fields.
     *
     * @param updateFields the list of fields to update or add
     * @return a new Paragraph with the updated fields
     */
    public Paragraph withFields(final List<ControlField> updateFields) {
      Map<String, ControlField> index = updateFields.stream().collect(Collectors.toMap(f -> f.key, f -> f));
      return new Paragraph(Stream
          .concat(fields.stream().filter(f -> !index.containsKey(f.key)), updateFields.stream())
          .collect(Collectors.toList()));
    }

    @Override
    public String toString() {
      return fields.stream()
          .map(f -> f.key + ": " + f.value)
          .collect(Collectors.joining("\n"));
    }
  }

  /**
   * Represents a field in a Debian control file, consisting of a key and value.
   */
  public static class ControlField
  {
    public final String key;

    public final String value;

    /**
     * Constructs a new ControlField with the given key and value.
     *
     * @param key the field key
     * @param value the field value
     */
    public ControlField(final String key, final String value) {
      super();
      this.key = key;
      this.value = value;
    }

    /**
     * Returns the value with all newlines and leading/trailing whitespace removed.
     *
     * @return the folded value as a single line
     */
    public String foldedValue() {
      return Arrays.stream(value.split("\n"))
          .map(String::trim)
          .collect(Collectors.joining());
    }

    /**
     * Splits the value by whitespace and returns it as a list of strings.
     *
     * @return the value split into a list of strings
     */
    public List<String> listValue() {
      return Arrays.asList(value.trim().split("\\s+"));
    }
  }
}