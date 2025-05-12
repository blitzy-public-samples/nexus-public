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

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * Parses and compares Debian version strings according to Debian's versioning policy.
 * 
 * More info about debian version could be found by ref:
 *
 * @see <a href="https://www.debian.org/doc/debian-policy/ch-controlfields.html#id6">https://www.debian.org/doc/</a>
 * @since 3.17
 * @updated Optimized for Java 21 with pattern matching and modern language features
 */
public class DebianVersion
    implements Comparable<DebianVersion>
{
  private static final Pattern VERSION_PART = Pattern.compile("(\\D*)(\\d*)");

  private final int epoch;

  private final String debianRevision;

  private final String upstreamVersion;

  /**
   * Constructs a new DebianVersion by parsing the provided version string.
   *
   * @param version the Debian version string to parse
   * @throws NullPointerException if version is null
   */
  public DebianVersion(final String version) {
    requireNonNull(version, "Version cannot be null");
    int colonIndex = version.indexOf(':');
    int hyphenIndex = version.lastIndexOf('-');

    this.epoch = parseEpoch(version, colonIndex);
    this.debianRevision = parseDebianRevision(version, hyphenIndex);
    this.upstreamVersion = parseUpstreamVersion(version, colonIndex, hyphenIndex);
  }

  /**
   * Returns the epoch component of the version.
   *
   * @return the epoch value (0 if not specified)
   */
  public int getEpoch() {
    return epoch;
  }

  /**
   * Returns the Debian revision component of the version.
   *
   * @return the Debian revision string (empty if not specified)
   */
  public String getDebianRevision() {
    return debianRevision;
  }

  /**
   * Returns the upstream version component.
   *
   * @return the upstream version string
   */
  public String getUpstreamVersion() {
    return upstreamVersion;
  }

  @Override
  public String toString() {
    return STR."{epoch > 0 ? STR."{epoch}:" : ""}{upstreamVersion}{debianRevision.isEmpty() ? "" : STR."-{debianRevision}"}";
  }

  @Override
  public int compareTo(final DebianVersion o) {
    // Compare epoch first
    int epochComparison = Integer.compare(this.epoch, o.epoch);
    if (epochComparison != 0) {
      return epochComparison;
    }
    
    // If epochs are equal, compare upstream versions
    int upstreamComparison = compareDebianVersion(this.upstreamVersion, o.upstreamVersion);
    if (upstreamComparison != 0) {
      return upstreamComparison;
    }
    
    // If upstream versions are equal, compare Debian revisions
    return compareDebianVersion(this.debianRevision, o.debianRevision);
  }

  @Override
  public boolean equals(final Object obj) {
    if (this == obj) {
      return true;
    }
    if (obj instanceof DebianVersion other) {
      return epoch == other.epoch &&
          Objects.equals(debianRevision, other.debianRevision) &&
          Objects.equals(upstreamVersion, other.upstreamVersion);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(epoch, debianRevision, upstreamVersion);
  }

  private int parseEpoch(final String version, final int colonIndex) {
    return colonIndex > 0 ? Integer.parseInt(version.substring(0, colonIndex)) : 0;
  }

  private String parseUpstreamVersion(final String version, final int colonIndex, final int hyphenIndex) {
    final int beginIndex = colonIndex > 0 ? colonIndex + 1 : 0;
    final int endIndex = hyphenIndex > 0 ? hyphenIndex : version.length();
    return version.substring(beginIndex, endIndex);
  }

  private String parseDebianRevision(final String version, final int hyphenIndex) {
    return hyphenIndex > 0 ? version.substring(hyphenIndex + 1) : "";
  }

  /**
   * Compares two Debian version strings according to Debian's version comparison algorithm.
   * 
   * @param a first version string to compare
   * @param b second version string to compare
   * @return negative if a < b, positive if a > b, zero if equal
   */
  private static int compareDebianVersion(final String a, final String b) {
    Matcher ma = VERSION_PART.matcher(a);
    Matcher mb = VERSION_PART.matcher(b);

    String nonNumericA = "";
    String numericA = "";
    String nonNumericB = "";
    String numericB = "";
    
    do {
      // Extract the next parts from each version string
      if (ma.find()) {
        nonNumericA = ma.group(1);
        numericA = ma.group(2);
      }
      else {
        nonNumericA = "";
        numericA = "";
      }
      
      if (mb.find()) {
        nonNumericB = mb.group(1);
        numericB = mb.group(2);
      }
      else {
        nonNumericB = "";
        numericB = "";
      }

      // Compare the non-numeric parts first
      int nonNumericComparison = compareNonNumeric(nonNumericA, nonNumericB);
      if (nonNumericComparison != 0) {
        return nonNumericComparison;
      }

      // Then compare the numeric parts
      int numericComparison = compareNumeric(numericA, numericB);
      if (numericComparison != 0) {
        return numericComparison;
      }
    }
    while (numericA.length() > 0 || nonNumericA.length() > 0 || 
           numericB.length() > 0 || nonNumericB.length() > 0);

    return 0;
  }

  /**
   * Compares the non-numeric parts of version strings character by character.
   * 
   * @param a first non-numeric string
   * @param b second non-numeric string
   * @return comparison result (-1, 0, or 1)
   */
  private static int compareNonNumeric(final String a, final String b) {
    int len = Math.max(a.length(), b.length());
    for (int i = 0; i < len; i++) {
      int charA = i >= a.length() ? -1 : a.codePointAt(i);
      int charB = i >= b.length() ? -1 : b.codePointAt(i);

      // Compare priority classes first
      int priorityA = priorityClass(charA);
      int priorityB = priorityClass(charB);
      
      if (priorityA != priorityB) {
        return Integer.compare(priorityA, priorityB);
      }
      
      // If priority classes are equal, compare the actual characters
      if (charA != charB) {
        return Integer.compare(charA, charB);
      }
    }

    return 0;
  }

  /**
   * Compares the numeric parts of version strings as long integers.
   * 
   * @param a first numeric string
   * @param b second numeric string
   * @return comparison result (-1, 0, or 1)
   */
  private static int compareNumeric(final String a, final String b) {
    // Handle empty strings specially
    return switch(a.isEmpty() + "-" + b.isEmpty()) {
      case "true-true" -> 0;  // Both empty
      case "true-false" -> -1; // First empty, second not
      case "false-true" -> 1;  // First not empty, second empty
      default -> Long.compare(Long.parseLong(a), Long.parseLong(b)); // Compare as numbers
    };
  }

  /**
   * Determines the priority class of a character for Debian version comparison.
   * 
   * @param c the character to classify
   * @return priority class value (-2, -1, 0, or 1)
   */
  private static int priorityClass(final int c) {
    return switch(c) {
      case '~' -> -2;      // Tilde sorts before everything
      case -1 -> -1;       // End of string sorts before most characters
      default -> Character.isLetter(c) ? 0 : 1; // Letters before other characters
    };
  }
}