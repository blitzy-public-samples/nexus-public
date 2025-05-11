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
package org.sonatype.nexus.repository.maven.internal.filter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;

import org.apache.maven.index.reader.Record;

import static org.sonatype.nexus.repository.maven.internal.utils.RecordUtils.gavceForRecord;

/**
 * Detects duplicates using an in memory map of hashes calculated from GAV-CE. This has an upper limit and will struggle
 * on most machines to check for duplicates on central. It is, however, guaranteed to be correct and is quick.
 * 
 * Note: This implementation uses MD5 for hash generation. While MD5 is not suitable for security-critical applications,
 * it is appropriate here for duplicate detection where cryptographic security is not required.
 *
 * @since 3.11
 */
public class HashBasedDuplicateDetectionStrategy
    implements DuplicateDetectionStrategy<Record>
{
  private final Set<String> gavces = new HashSet<>();
  private final MessageDigest md5;
  
  /**
   * Constructs a new duplicate detection strategy using MD5 hashing.
   * 
   * @throws IllegalStateException if MD5 algorithm is not available
   */
  public HashBasedDuplicateDetectionStrategy() {
    try {
      this.md5 = MessageDigest.getInstance("MD5");
    } 
    catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 algorithm not available", e);
    }
  }

  @Override
  public boolean apply(final Record record) {
    if (record == null) {
      return false;
    }
    
    String gavce = gavceForRecord(record);
    byte[] hashBytes = md5.digest(gavce.getBytes(StandardCharsets.UTF_8));
    String hashHex = bytesToHex(hashBytes);
    
    return gavces.add(hashHex);
  }
  
  /**
   * Converts a byte array to a hexadecimal string.
   * 
   * @param bytes the byte array to convert
   * @return the hexadecimal representation
   */
  private static String bytesToHex(byte[] bytes) {
    StringBuilder hexString = new StringBuilder(2 * bytes.length);
    for (byte b : bytes) {
      String hex = Integer.toHexString(0xff & b);
      if (hex.length() == 1) {
        hexString.append('0');
      }
      hexString.append(hex);
    }
    return hexString.toString();
  }
}