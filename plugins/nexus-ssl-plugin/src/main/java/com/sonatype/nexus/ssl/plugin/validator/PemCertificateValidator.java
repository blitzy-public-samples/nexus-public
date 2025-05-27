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
import java.security.cert.CertificateParsingException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateEncodingException;

import javax.validation.ConstraintValidatorContext;

import org.sonatype.nexus.ssl.CertificateUtil;
import org.sonatype.nexus.validation.ConstraintValidatorSupport;

/**
 * {@link PemCertificate} validator.
 *
 * @since 3.0
 */
public class PemCertificateValidator
    extends ConstraintValidatorSupport<PemCertificate, String>
{
  @Override
  public boolean isValid(final String value, final ConstraintValidatorContext context) {
    try {
      CertificateUtil.decodePEMFormattedCertificate(value);
      return true;
    }
    catch (CertificateException e) {
      // Disable the default constraint violation message
      context.disableDefaultConstraintViolation();
      
      // Use pattern matching for switch to handle different types of certificate exceptions
      switch (e) {
        case CertificateParsingException pe -> {
          String message = "Invalid certificate format: The certificate could not be parsed correctly. " + 
                          "Please ensure it is a valid X.509 certificate in PEM format. Details: " + pe.getMessage();
          context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
        case CertificateExpiredException ee -> {
          String message = "Certificate has expired: The certificate is no longer valid as its expiration date has passed. " + 
                          "Please provide a certificate with a valid date range. Details: " + ee.getMessage();
          context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
        case CertificateNotYetValidException nve -> {
          String message = "Certificate is not yet valid: The certificate's validity period has not started. " + 
                          "Please check the 'Not Before' date of the certificate. Details: " + nve.getMessage();
          context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
        case CertificateEncodingException cee -> {
          String message = "Certificate encoding error: There was a problem with the certificate's encoding. " + 
                          "This may indicate corruption or an unsupported format. Details: " + cee.getMessage();
          context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
        default -> {
          String message = "Invalid certificate: The certificate could not be validated. " + 
                          "Please ensure you are providing a valid X.509 certificate in PEM format. Details: " + e.getMessage();
          context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        }
      }
      return false;
    }
  }
}