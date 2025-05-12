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

import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Represents package information extracted from a Debian control file.
 * 
 * @since 3.17
 * @see ControlFile
 */
public class PackageInfo
{
  private static final String PACKAGE_FIELD = "Package";

  private static final String VERSION_FIELD = "Version";

  private static final String ARCHITECTURE_FIELD = "Architecture";

  private final ControlFile controlFile;

  /**
   * Creates a new package info instance from the given control file.
   *
   * @param controlFile the Debian control file containing package metadata
   */
  public PackageInfo(final ControlFile controlFile) {
    this.controlFile = controlFile;
  }

  /**
   * @return the underlying control file
   */
  public ControlFile getControlFile() {
    return controlFile;
  }

  /**
   * @return the package name
   * @throws NoSuchElementException if the package name field is not present
   */
  public String getPackageName() {
    return getField(PACKAGE_FIELD);
  }

  /**
   * @return the package version
   * @throws NoSuchElementException if the version field is not present
   */
  public String getVersion() {
    return getField(VERSION_FIELD);
  }

  /**
   * @return the package architecture
   * @throws NoSuchElementException if the architecture field is not present
   */
  public String getArchitecture() {
    return getField(ARCHITECTURE_FIELD);
  }

  /**
   * Retrieves a field value from the control file.
   *
   * @param fieldName the name of the field to retrieve
   * @return the field value
   * @throws NoSuchElementException if the field is not present
   */
  private String getField(final String fieldName) {
    Optional<ControlFile.ControlField> fieldOptional = controlFile.getField(fieldName);
    
    // Using Java 21 pattern matching for instanceof
    return switch (fieldOptional) {
      case Optional<ControlFile.ControlField> opt when opt.isPresent() -> opt.get().value;
      default -> throw new NoSuchElementException("Required field not found: " + fieldName);
    };
  }
}
