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
package org.sonatype.nexus.blobstore.s3.internal.ui;

import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link S3Component}.
 * 
 * This test class verifies that the S3Component correctly provides information about
 * available S3 regions, signer types, and encryption types for the S3 blobstore.
 * 
 * @since 3.12
 */
@DisplayName("S3Component Configuration Tests")
public class S3ComponentTest
    extends TestSupport
{
  private S3Component underTest;

  /**
   * Set up a new S3Component instance before each test.
   */
  @BeforeEach
  void setUp() {
    underTest = new S3Component();
  }

  /**
   * Verifies that the S3Component returns the expected default region.
   */
  @Test
  @DisplayName("Should return the default region")
  void regionsReturnsDefaultRegion() {
    List<S3RegionXO> regions = underTest.regions();
    assertRegion(regions.get(0), 0, "DEFAULT", "Default");
  }

  /**
   * Verifies that the S3Component returns the expected signer types.
   */
  @Test
  @DisplayName("Should return all supported signer types")
  void signerTypesReturnsAllSupportedTypes() {
    List<S3SignerTypeXO> signerTypes = underTest.signertypes();
    assertSignerType(signerTypes.get(0), 0, "DEFAULT", "Default");
    assertSignerType(signerTypes.get(1), 1, "S3SignerType", "S3SignerType");
    assertSignerType(signerTypes.get(2), 2, "AWSS3V4SignerType", "AWSS3V4SignerType");
  }

  /**
   * Verifies that the S3Component returns the expected encryption types.
   */
  @Test
  @DisplayName("Should return all supported encryption types")
  void encryptionTypesReturnsAllSupportedTypes() {
    List<S3EncryptionTypeXO> encryptionTypes = underTest.encryptionTypes();
    assertEncryptionType(encryptionTypes.get(0), 0, "none", "None");
    assertEncryptionType(encryptionTypes.get(1), 1, "s3ManagedEncryption", "S3 Managed Encryption");
    assertEncryptionType(encryptionTypes.get(2), 2, "kmsManagedEncryption", "KMS Managed Encryption");
  }

  /**
   * Helper method to assert that a region has the expected properties.
   * 
   * @param region the region to check
   * @param order the expected order
   * @param id the expected ID
   * @param name the expected name
   */
  private void assertRegion(final S3RegionXO region, final int order, final String id, final String name) {
    assertThat(region.getOrder(), is(order));
    assertThat(region.getId(), is(id));
    assertThat(region.getName(), is(name));
  }

  /**
   * Helper method to assert that a signer type has the expected properties.
   * 
   * @param signerType the signer type to check
   * @param order the expected order
   * @param id the expected ID
   * @param name the expected name
   */
  private void assertSignerType(final S3SignerTypeXO signerType, final int order, final String id, final String name) {
    assertThat(signerType.getOrder(), is(order));
    assertThat(signerType.getId(), is(id));
    assertThat(signerType.getName(), is(name));
  }

  /**
   * Helper method to assert that an encryption type has the expected properties.
   * 
   * @param encryptionType the encryption type to check
   * @param order the expected order
   * @param id the expected ID
   * @param name the expected name
   */
  private void assertEncryptionType(final S3EncryptionTypeXO encryptionType, final int order, final String id,
                                    final String name) {
    assertThat(encryptionType.getOrder(), is(order));
    assertThat(encryptionType.getId(), is(id));
    assertThat(encryptionType.getName(), is(name));
  }
}
