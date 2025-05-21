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
package org.sonatype.nexus.internal.node;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Locale;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.ssl.CertificateUtil;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Node ID encoding helpers.
 *
 * @since 3.0
 */
public class NodeIdEncoding
{
  private NodeIdEncoding() {
    // empty
  }

  /**
   * Encode plain Certificate SHA1 into node-id string.
   * 
   * @deprecated SHA-1 is considered insecure. Use {@link #nodeIdForSha256(String)} instead.
   */
  @Deprecated
  public static String nodeIdForSha1(final String input) {
    checkNotNull(input);
    return Strings2.encodeSeparator(input, '-', 8);
  }

  /**
   * Decode node-id into plain SHA1 string.
   * 
   * @deprecated SHA-1 is considered insecure. Use {@link #sha256ForNodeId(String)} instead.
   */
  @Deprecated
  public static String sha1ForNodeId(final String input) {
    checkNotNull(input);
    return input.replaceAll("-", "");
  }

  /**
   * Encode plain Certificate SHA-256 into node-id string.
   * 
   * @since 3.60
   */
  public static String nodeIdForSha256(final String input) {
    checkNotNull(input);
    return STR."\{Strings2.encodeSeparator(input, '-', 8)}";
  }

  /**
   * Decode node-id into plain SHA-256 string.
   * 
   * @since 3.60
   */
  public static String sha256ForNodeId(final String input) {
    checkNotNull(input);
    return STR."\{input.replaceAll("-", "")}";
  }

  /**
   * Return node-id for certificate using SHA-1.
   * 
   * @deprecated SHA-1 is considered insecure. Use {@link #nodeIdForCertificateSha256(Certificate)} instead.
   */
  @Deprecated
  public static String nodeIdForCertificate(final Certificate cert) throws CertificateEncodingException {
    checkNotNull(cert);
    String sha1 = CertificateUtil.calculateSha1(cert);
    return nodeIdForSha1(sha1);
  }

  /**
   * Return node-id for certificate using SHA-256.
   * 
   * @since 3.60
   */
  public static String nodeIdForCertificateSha256(final Certificate cert) throws CertificateEncodingException {
    checkNotNull(cert);
    // Use MessageDigest directly to calculate SHA-256 hash of certificate
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] encodedCert = cert.getEncoded();
      byte[] hashBytes = digest.digest(encodedCert);
      
      // Convert to hex string
      StringBuilder hexString = new StringBuilder();
      for (byte b : hashBytes) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      String sha256 = hexString.toString().toUpperCase(Locale.US);
      return nodeIdForSha256(sha256);
    }
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Return node-id for certificate fingerprint using SHA-1.
   * 
   * @deprecated SHA-1 is considered insecure. Use {@link #nodeIdForFingerprintSha256(String)} instead.
   */
  @Deprecated
  public static String nodeIdForFingerprint(final String fingerprint) {
    checkNotNull(fingerprint);
    String sha1 = fingerprint.replace(":", "");
    return nodeIdForSha1(sha1);
  }

  /**
   * Return node-id for certificate fingerprint using SHA-256.
   * 
   * @since 3.60
   */
  public static String nodeIdForFingerprintSha256(final String fingerprint) {
    checkNotNull(fingerprint);
    // Use String Template for string manipulation
    String sha256 = STR."\{fingerprint.replace(":", "")}";
    return nodeIdForSha256(sha256);
  }
}