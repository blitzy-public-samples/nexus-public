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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.apt.java21.Java21TestGroup;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * @since 3.17
 */
public class DebianVersionTest
    extends TestSupport
{
  private final static String LOWER_EPOCH_UPSTREAM_DEBIAN = "2:7.3.429-2ubuntu2.1";

  private final static String HIGHER_EPOCH_UPSTREAM_DEBIAN = "3:7.3.429-2ubuntu2.1";

  private final static String UPSTREAM = "0.13";

  private final static String UPSTREAM_DEBIAN = "1.11-1";

  private final static String UPSTREAM_DEBIAN_TILDE = "30~pre9-5ubuntu2";
  
  /**
   * Record to demonstrate Record Patterns with Pattern Matching
   * Contains two DebianVersion objects for comparison
   */
  record VersionPair(DebianVersion first, DebianVersion second) {}

  @Test
  public void testCompareVersionIsSimilar() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).compareTo(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN)),
        is(0));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN).compareTo(new DebianVersion(UPSTREAM_DEBIAN)), is(0));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN_TILDE).compareTo(new DebianVersion(UPSTREAM_DEBIAN_TILDE)), is(0));
    assertThat(new DebianVersion(UPSTREAM).compareTo(new DebianVersion(UPSTREAM)), is(0));
  }

  @Test
  public void testEqualityVersionIsSimilar() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN),
        is(equalTo(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN))));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN), is(equalTo(new DebianVersion(UPSTREAM_DEBIAN))));
    assertThat(new DebianVersion(UPSTREAM_DEBIAN_TILDE), is(equalTo(new DebianVersion(UPSTREAM_DEBIAN_TILDE))));
    assertThat(new DebianVersion(UPSTREAM), is(equalTo(new DebianVersion(UPSTREAM))));
  }

  @Test
  public void testCompareLowerEpochVersion() {
    assertThat(
        new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN).compareTo(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN)),
        is(1));
  }

  @Test
  public void testCompareHigherEpochVersion() {
    assertThat(
        new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).compareTo(new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN)),
        is(-1));
  }

  @Test
  public void testVerifyEpochVersionParthIsCorrect_IfAllPartsPresent() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).getEpoch(), is(equalTo(2)));
  }

  @Test
  public void testVerifyEpochVersionPartIsZero_IfEpochNotExist() {
    assertThat(new DebianVersion(UPSTREAM).getEpoch(), is(equalTo(0)));
  }

  @Test
  public void testVerifyUpstreamVersionPartIsCorrect_IfAllPartsPresent() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).getUpstreamVersion(), is(equalTo("7.3.429")));
  }

  @Test
  public void testVerifyUpstreamVersionPartIsCorrect_IfThereAreNoEpochAndDebian() {
    assertThat(new DebianVersion(UPSTREAM).getUpstreamVersion(), is(equalTo("0.13")));
  }

  @Test
  public void testVerifyDebianVersionPartIsCorrect_IfAllPartsPresent() {
    assertThat(new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN).getDebianRevision(), is(equalTo("2ubuntu2.1")));
  }
  
  /**
   * Test version comparison using Java 21 Pattern Matching for switch
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testCompareVersionsWithPatternMatching() {
    VersionPair equalVersions = new VersionPair(
        new DebianVersion(UPSTREAM_DEBIAN), 
        new DebianVersion(UPSTREAM_DEBIAN));
    
    VersionPair higherEpochVersions = new VersionPair(
        new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN),
        new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN));
    
    VersionPair lowerEpochVersions = new VersionPair(
        new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN),
        new DebianVersion(HIGHER_EPOCH_UPSTREAM_DEBIAN));
    
    // Using Pattern Matching for switch to determine comparison result
    int equalResult = switch (equalVersions) {
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) == 0 -> 0;
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) > 0 -> 1;
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) < 0 -> -1;
      default -> throw new IllegalStateException("Unexpected comparison result");
    };
    
    int higherResult = switch (higherEpochVersions) {
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) == 0 -> 0;
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) > 0 -> 1;
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) < 0 -> -1;
      default -> throw new IllegalStateException("Unexpected comparison result");
    };
    
    int lowerResult = switch (lowerEpochVersions) {
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) == 0 -> 0;
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) > 0 -> 1;
      case VersionPair(DebianVersion first, DebianVersion second) when first.compareTo(second) < 0 -> -1;
      default -> throw new IllegalStateException("Unexpected comparison result");
    };
    
    assertThat(equalResult, is(0));
    assertThat(higherResult, is(1));
    assertThat(lowerResult, is(-1));
  }
  
  /**
   * Test version parsing using Java 21 Pattern Matching for switch
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testVersionParsingWithPatternMatching() {
    DebianVersion epochVersion = new DebianVersion(LOWER_EPOCH_UPSTREAM_DEBIAN);
    DebianVersion upstreamVersion = new DebianVersion(UPSTREAM);
    DebianVersion upstreamDebianVersion = new DebianVersion(UPSTREAM_DEBIAN);
    
    // Using Pattern Matching for switch to determine version type
    String versionType = switch (epochVersion) {
      case DebianVersion v when v.getEpoch() > 0 && v.getDebianRevision() != null -> "Full version with epoch";
      case DebianVersion v when v.getEpoch() == 0 && v.getDebianRevision() != null -> "Version with debian revision";
      case DebianVersion v when v.getEpoch() == 0 && v.getDebianRevision() == null -> "Upstream version only";
      default -> "Unknown version format";
    };
    
    String upstreamVersionType = switch (upstreamVersion) {
      case DebianVersion v when v.getEpoch() > 0 && v.getDebianRevision() != null -> "Full version with epoch";
      case DebianVersion v when v.getEpoch() == 0 && v.getDebianRevision() != null -> "Version with debian revision";
      case DebianVersion v when v.getEpoch() == 0 && v.getDebianRevision() == null -> "Upstream version only";
      default -> "Unknown version format";
    };
    
    String upstreamDebianVersionType = switch (upstreamDebianVersion) {
      case DebianVersion v when v.getEpoch() > 0 && v.getDebianRevision() != null -> "Full version with epoch";
      case DebianVersion v when v.getEpoch() == 0 && v.getDebianRevision() != null -> "Version with debian revision";
      case DebianVersion v when v.getEpoch() == 0 && v.getDebianRevision() == null -> "Upstream version only";
      default -> "Unknown version format";
    };
    
    assertThat(versionType, is("Full version with epoch"));
    assertThat(upstreamVersionType, is("Upstream version only"));
    assertThat(upstreamDebianVersionType, is("Version with debian revision"));
  }
  
  /**
   * Test nested pattern matching with record patterns
   */
  @Test
  @Category(Java21TestGroup.class)
  public void testNestedPatternMatching() {
    // Create a pair of version pairs for more complex pattern matching
    var nestedPair = new VersionPair(
        new DebianVersion(UPSTREAM_DEBIAN),
        new DebianVersion(UPSTREAM_DEBIAN_TILDE)
    );
    
    // Using nested pattern matching to extract components
    String result = switch (nestedPair) {
      case VersionPair(DebianVersion first, DebianVersion second) -> {
        String firstUpstream = first.getUpstreamVersion();
        String secondUpstream = second.getUpstreamVersion();
        yield "First upstream: " + firstUpstream + ", Second upstream: " + secondUpstream;
      }
      default -> "No match";
    };
    
    assertThat(result, is("First upstream: 1.11, Second upstream: 30"));
  }
}