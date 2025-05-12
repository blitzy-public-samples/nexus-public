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

import java.util.List;
import java.util.Optional;

/**
 * Represents a Debian Release file containing metadata about a repository.
 * 
 * @since 3.17
 * @see <a href="https://wiki.debian.org/DebianRepository/Format#A.22Release.22_files">Debian Release File Format</a>
 */
public class Release
{
  private final ControlFile index;

  /**
   * Creates a new Release instance from a control file.
   *
   * @param index the control file containing the release metadata
   */
  public Release(final ControlFile index) {
    this.index = index;
  }

  /**
   * Gets the origin of the repository.
   *
   * @return the origin value, if present
   */
  public Optional<String> getOrigin() {
    return getValue("Origin");
  }

  /**
   * Gets the label of the repository.
   *
   * @return the label value, if present
   */
  public Optional<String> getLabel() {
    return getValue("Label");
  }

  /**
   * Gets the suite of the repository.
   *
   * @return the suite value, if present
   */
  public Optional<String> getSuite() {
    return getValue("Suite");
  }

  /**
   * Gets the version of the repository.
   *
   * @return the version value, if present
   */
  public Optional<String> getVersion() {
    return getValue("Version");
  }

  /**
   * Gets the codename of the repository.
   *
   * @return the codename value, if present
   */
  public Optional<String> getCodename() {
    return getValue("Codename");
  }

  /**
   * Gets the list of components in the repository.
   *
   * @return the list of components, or an empty list if not present
   */
  public List<String> getComponents() {
    return index.getField("Components")
        .map(ControlFile.ControlField::listValue)
        .orElse(List.of());
  }

  /**
   * Gets the list of architectures supported by the repository.
   *
   * @return the list of architectures, or an empty list if not present
   */
  public List<String> getArchitectures() {
    return index.getField("Architectures")
        .map(ControlFile.ControlField::listValue)
        .orElse(List.of());
  }

  /**
   * Gets the description of the repository.
   *
   * @return the description value, if present
   */
  public Optional<String> getDescription() {
    return getValue("Description");
  }

  /**
   * Helper method to retrieve a field value by name.
   *
   * @param name the field name to retrieve
   * @return the field value, if present
   */
  private Optional<String> getValue(final String name) {
    return index.getField(name).map(field -> field.value);
  }
}
