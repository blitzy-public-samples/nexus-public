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

package org.sonatype.nexus.content.maven.store;

import java.util.Objects;

import org.sonatype.nexus.repository.content.store.ComponentData;

/**
 * Maven 2 specific component data.
 * 
 * This class extends the base ComponentData to add Maven 2 specific attributes.
 * It has been updated for Java 21 compatibility and follows modern Java practices.
 * 
 * <p>When working with this class in Java 21, you can leverage pattern matching for instanceof
 * and record patterns for more concise code. For example:</p>
 * 
 * <pre>
 * {@code
 * // Pattern matching with instanceof
 * if (component instanceof Maven2ComponentData maven2Component) {
 *     String baseVersion = maven2Component.getBaseVersion();
 *     // Use baseVersion directly
 * }
 * 
 * // Using with enhanced switch expressions
 * String result = switch(component) {
 *     case Maven2ComponentData maven2Component -> maven2Component.getBaseVersion();
 *     default -> component.version();
 * };
 * }
 * </pre>
 *
 * @since 3.29
 */
public class Maven2ComponentData
    extends ComponentData
{
  private String baseVersion;

  /**
   * Returns the base version of the Maven component.
   * 
   * For release versions, this is the same as the version.
   * For snapshot versions, this is the version without the timestamp.
   *
   * @return the base version string
   */
  public String getBaseVersion() {
    return baseVersion;
  }

  /**
   * Sets the base version of the Maven component.
   *
   * @param baseVersion the base version to set
   */
  public void setBaseVersion(final String baseVersion) {
    this.baseVersion = baseVersion;
  }
  
  /**
   * Compares this Maven2ComponentData with another object for equality.
   * 
   * @param o the object to compare with
   * @return true if the objects are equal, false otherwise
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Maven2ComponentData that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return Objects.equals(baseVersion, that.baseVersion);
  }

  /**
   * Generates a hash code for this Maven2ComponentData.
   * 
   * @return the hash code
   */
  @Override
  public int hashCode() {
    return Objects.hash(super.hashCode(), baseVersion);
  }

  /**
   * Returns a string representation of this Maven2ComponentData.
   * 
   * @return a string representation
   */
  @Override
  public String toString() {
    return """
        Maven2ComponentData{baseVersion='%s', %s}
        """.formatted(baseVersion, super.toString());
  }
}