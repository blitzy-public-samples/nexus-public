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
package org.sonatype.nexus.security.authc.apikey;

import org.apache.shiro.subject.PrincipalCollection;

/**
 * API Key Factory that creates API Keys. If some specific content or format needed, implement one as SISU component and
 * use same name as your {@link ApiKeyExtractor} component has.
 * <p>
 * Implementations should use modern Java 21 cryptographic providers and secure random generation techniques to ensure
 * the highest level of security. The recommended approach is to use {@link java.security.SecureRandom} with appropriate
 * algorithm selection and proper seeding.
 * <p>
 * For Java 21 implementations, consider the following best practices:
 * <ul>
 *   <li>Use {@link java.security.SecureRandom#getInstanceStrong()} for generating long-term key material, but be aware
 *       that this may block on some platforms. For high-throughput scenarios, consider using a properly seeded
 *       instance of {@code SecureRandom.getInstance("SHA1PRNG", "SUN")} or the default constructor.</li>
 *   <li>Ensure all seed material is unpredictable, as required by RFC 4086 "Randomness Requirements for Security".</li>
 *   <li>Consider using BouncyCastle 1.78.1 or later as a cryptographic provider for enhanced security and compatibility
 *       with Java 21's security model.</li>
 *   <li>Take advantage of Java 21's enhanced JCE landscape, particularly the improved AES-GCM acceleration via SunJCE.</li>
 * </ul>
 * <p>
 * When implementing this interface, consider using Java 21's pattern matching for switch to handle different principal types:
 * <pre>{@code
 * public char[] makeApiKey(final PrincipalCollection principals) {
 *     Object primaryPrincipal = principals.getPrimaryPrincipal();
 *     
 *     // Using Java 21 pattern matching for switch
 *     return switch (primaryPrincipal) {
 *         case String username -> generateApiKeyForUsername(username);
 *         case UserDetails userDetails -> generateApiKeyForUserDetails(userDetails);
 *         case null -> throw new IllegalArgumentException("Primary principal cannot be null");
 *         default -> generateDefaultApiKey(primaryPrincipal);
 *     };
 * }
 * }</pre>
 *
 * @since 3.0
 */
public interface ApiKeyFactory
{
  /**
   * Creates a domain specific API Key, never {@code null}.
   * <p>
   * Implementations must use cryptographically strong random number generation to ensure
   * the security of the generated API keys. The generated keys should have sufficient
   * entropy to resist brute force attacks and should not be predictable.
   * <p>
   * In Java 21, implementations should leverage the enhanced security features and
   * cryptographic providers to generate secure API keys.
   *
   * @param principals the principal collection for which to create an API key
   * @return the generated API key as a character array (for security reasons)
   * @throws IllegalArgumentException if the principals collection is invalid or insufficient
   */
  char[] makeApiKey(final PrincipalCollection principals);
}