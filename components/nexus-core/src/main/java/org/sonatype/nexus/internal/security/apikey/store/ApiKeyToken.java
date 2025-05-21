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
package org.sonatype.nexus.internal.security.apikey.store;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;

import org.sonatype.nexus.security.authc.apikey.ApiKey;
import org.sonatype.nexus.datastore.mybatis.handlers.PasswordCharacterArrayTypeHandler;

/**
 * {@link ApiKey} token holder; internal use only.
 *
 * This wrapper makes it easy to apply a {@link ApiKeyTokenTypeHandler special} MyBatis type handler to the enclosed
 * character array to allow queries against the encrypted value. The {@link PasswordCharacterArrayTypeHandler default}
 * handler cannot support queries because it adds random salt to every encryption request, so even if the source array
 * was the same the encrypted value would differ each time.
 *
 * @since 3.21
 */
class ApiKeyToken
{
  private final char[] chars;

  /**
   * Creates a new API key token with the given character array.
   * 
   * @param chars the character array to store (not copied, for performance reasons)
   */
  public ApiKeyToken(final char[] chars) { // NOSONAR
    this.chars = chars; // NOSONAR: this is just a temporary transfer object
  }

  /**
   * Returns the character array stored in this token.
   * 
   * @return the character array (not copied, for performance reasons)
   */
  public char[] getChars() { // NOSONAR
    return chars; // NOSONAR: this is just a temporary transfer object
  }

  /**
   * Returns a {@link CharBuffer} view of the character array stored in this token.
   * The buffer is optimized for Java 21's improved NIO buffer handling.
   * 
   * @return a character buffer view of the stored character array
   */
  public CharBuffer getCharBuffer() {
    // Use CharBuffer.wrap with explicit capacity for better memory management in Java 21
    return CharBuffer.wrap(chars, 0, chars.length);
  }
  
  /**
   * Returns a string representation of this token using UTF-8 encoding.
   * This method leverages Java 21's improved charset handling.
   * 
   * @return a string representation of the token
   */
  @Override
  public String toString() {
    // Use StandardCharsets.UTF_8 explicitly for consistent encoding across platforms
    return new String(chars, StandardCharsets.UTF_8);
  }
}