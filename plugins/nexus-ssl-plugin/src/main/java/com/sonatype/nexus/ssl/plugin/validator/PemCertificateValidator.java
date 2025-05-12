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
package com.sonatype.nexus.ssl.plugin.validator;

import java.security.cert.CertificateException;

import javax.validation.ConstraintValidatorContext;

import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.validation.ConstraintValidatorSupport;

/**
 * {@link PemCertificate} validator.
 * 
 * This validator checks if a string contains a valid PEM-formatted certificate
 * by attempting to decode it using {@link CertificateUtil#decodePEMFormattedCertificate}.
 *
 * @since 3.0
 * @see CertificateUtil#decodePEMFormattedCertificate(String)
 */
public class PemCertificateValidator
    extends ConstraintValidatorSupport<PemCertificate, String>
{
  /**
   * Validates if the provided string value is a valid PEM-formatted certificate.
   *
   * @param value   the string to validate as a PEM certificate
   * @param context the constraint validator context
   * @return true if the string is a valid PEM certificate, false otherwise
   */
  @Override
  public boolean isValid(final String value, final ConstraintValidatorContext context) {
    if (value == null || value.isBlank()) {
      return false;
    }
    
    try {
      CertificateUtil.decodePEMFormattedCertificate(value);
      return true;
    }
    catch (CertificateException e) {
      // Using Java 21 pattern matching for exception handling
      return switch (e) {
        case java.security.cert.CertificateParsingException cpe -> {
          // Log more specific information about parsing failures if needed
          yield false;
        }
        default -> false;
      };
    }
  }
}