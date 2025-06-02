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
package org.sonatype.nexus.security.internal;

import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.authc.credential.DefaultPasswordService;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.crypto.hash.DefaultHashService;
import org.apache.shiro.crypto.hash.format.HexFormat;
import org.apache.shiro.crypto.SecureRandomNumberGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Legacy {@link PasswordService}.
 *
 * PasswordService for handling legacy passwords (SHA-1 and MD5).
 * 
 * <p>
 * Note: SHA-1 and MD5 are considered cryptographically weak algorithms and should not be used
 * for new password storage. This service exists only to support legacy password verification.
 * </p>
 * 
 * <p>
 * This implementation has been updated for Java 21 compatibility, ensuring proper handling
 * of legacy hash formats while leveraging security enhancements where possible.
 * </p>
 */
@Named("legacy")
@Singleton
public class LegacyNexusPasswordService
    implements PasswordService
{
  private static final Logger log = LoggerFactory.getLogger(LegacyNexusPasswordService.class);
  
  private final DefaultPasswordService sha1PasswordService;

  private final DefaultPasswordService md5PasswordService;

  public LegacyNexusPasswordService() {
    // Create a secure random number generator optimized for Java 21
    SecureRandomNumberGenerator secureRng = new SecureRandomNumberGenerator();
    try {
      // Use the strongest available algorithm
      secureRng.setAlgorithmName("NativePRNGNonBlocking");
    } catch (Exception e) {
      // Fall back to default if the algorithm is not available
      log.debug("NativePRNGNonBlocking not available, using default SecureRandom algorithm", e);
    }
    
    //Initialize and configure sha1 password service
    this.sha1PasswordService = new DefaultPasswordService();
    DefaultHashService sha1HashService = new DefaultHashService();
    sha1HashService.setHashAlgorithmName("SHA-1");
    sha1HashService.setHashIterations(1);
    sha1HashService.setGeneratePublicSalt(false);
    // Set the secure random number generator
    sha1HashService.setRandomNumberGenerator(secureRng);
    this.sha1PasswordService.setHashService(sha1HashService);
    this.sha1PasswordService.setHashFormat(new HexFormat());

    //Initialize and configure md5 password service
    this.md5PasswordService = new DefaultPasswordService();
    DefaultHashService md5HashService = new DefaultHashService();
    md5HashService.setHashAlgorithmName("MD5");
    md5HashService.setHashIterations(1);
    md5HashService.setGeneratePublicSalt(false);
    // Set the secure random number generator
    md5HashService.setRandomNumberGenerator(secureRng);
    this.md5PasswordService.setHashService(md5HashService);
    this.md5PasswordService.setHashFormat(new HexFormat());
  }

  /**
   * Encryption is not supported by this legacy service.
   * 
   * @throws UnsupportedOperationException always thrown as this operation is not supported
   */
  @Override
  public String encryptPassword(final Object plaintextPassword) {
    throw new UnsupportedOperationException("Legacy password service does not support encryption, only verification");
  }

  /**
   * Verifies if the submitted plaintext password matches the encrypted password.
   * 
   * <p>
   * This method checks both SHA-1 and MD5 hash formats to support legacy passwords.
   * The implementation is optimized for Java 21 and handles potential security exceptions.
   * </p>
   * 
   * @param submittedPlaintext the plaintext password submitted for verification
   * @param encrypted the encrypted password to compare against
   * @return true if the passwords match, false otherwise
   */
  @Override
  public boolean passwordsMatch(final Object submittedPlaintext, final String encrypted) {
    if (submittedPlaintext == null || encrypted == null || encrypted.isEmpty()) {
      return false;
    }
    
    try {
      // Legacy passwords can be hashed with sha-1 or md5, check both
      return sha1PasswordService.passwordsMatch(submittedPlaintext, encrypted) ||
          md5PasswordService.passwordsMatch(submittedPlaintext, encrypted);
    } catch (Exception e) {
      // Log the error but don't expose details in the return value for security reasons
      log.warn("Error during password verification", e);
      return false;
    }
  }
}