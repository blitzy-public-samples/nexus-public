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
package com.sonatype.nexus.ssl.plugin.internal.ui;

/**
 * Certificate exchange object.
 * <p>
 * Implemented as a Java Record for immutability and automatic generation of
 * toString(), equals(), and hashCode() methods.
 *
 * @since 3.0
 */
public record CertificateXO(
    String id,
    String fingerprint,
    String pem,
    String serialNumber,
    String subjectCommonName,
    String subjectOrganization,
    String subjectOrganizationalUnit,
    String issuerCommonName,
    String issuerOrganization,
    String issuerOrganizationalUnit,
    long issuedOn,
    long expiresOn,
    boolean inTrustStore
) {
  /**
   * Creates a minimal certificate exchange object with only id, fingerprint, and PEM data.
   * All other fields will be initialized with default values.
   *
   * @param id the certificate identifier
   * @param fingerprint the certificate fingerprint
   * @param pem the certificate in PEM format
   */
  public CertificateXO(final String id, final String fingerprint, final String pem) {
    this(id, fingerprint, pem, null, null, null, null, null, null, null, 0L, 0L, false);
  }
}