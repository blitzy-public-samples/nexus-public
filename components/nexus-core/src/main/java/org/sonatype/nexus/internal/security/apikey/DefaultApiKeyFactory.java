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
package org.sonatype.nexus.internal.security.apikey;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.crypto.RandomBytesGenerator;
import org.sonatype.nexus.security.authc.apikey.ApiKeyFactory;

import org.apache.shiro.subject.PrincipalCollection;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link ApiKeyFactory} that creates random UUID.
 *
 * @since 3.0
 */
@Named("default")
@Singleton
public class DefaultApiKeyFactory
    extends ComponentSupport
    implements ApiKeyFactory
{
  private final RandomBytesGenerator randomBytesGenerator;

  @Inject
  public DefaultApiKeyFactory(final RandomBytesGenerator randomBytesGenerator) {
    this.randomBytesGenerator = checkNotNull(randomBytesGenerator);
  }

  @Override
  public char[] makeApiKey(final PrincipalCollection principals) {
    try {
      // Generate a larger salt using Java 21's stronger random number generation
      final byte[] salt = randomBytesGenerator.generate(16); // Increased from 4 to 16 bytes for stronger security
      final String saltHex = new BigInteger(1, salt).toString(32);
      
      // Create a more secure input for the UUID generation
      final String input = STR."~nexus~default~\{principals}\{saltHex}";
      final byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
      
      // Use SHA-256 for additional security before UUID generation
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(inputBytes);
      
      // Generate UUID from the hash
      final String apiKey = UUID.nameUUIDFromBytes(hash).toString();
      
      log.debug(STR."Generated API key for principal: \{principals} with salt length: \{salt.length} bytes");
      
      return apiKey.toCharArray();
    }
    catch (NoSuchAlgorithmException e) {
      log.error(STR."Failed to generate API key for principal: \{principals}", e);
      throw new RuntimeException(STR."Error generating API key: \{e.getMessage()}", e);
    }
  }
}