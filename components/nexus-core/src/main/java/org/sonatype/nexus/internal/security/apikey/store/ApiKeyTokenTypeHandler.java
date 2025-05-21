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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.mybatis.CipherAwareTypeHandler;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * MyBatis {@link TypeHandler} that encrypts {@link ApiKeyToken}s at rest with the property that the same
 * token will always be encrypted to the same database string, assuming the same database cipher settings.
 * This is needed to support searching by token, otherwise we could have used a plain char array without
 * needing the {@link ApiKeyToken} wrapper.
 *
 * @since 3.21
 */
@Named
@Singleton
public class ApiKeyTokenTypeHandler
    extends CipherAwareTypeHandler<ApiKeyToken>
{
  @Override
  public final void setNonNullParameter(
      final PreparedStatement ps,
      final int parameterIndex,
      final ApiKeyToken parameter,
      final JdbcType jdbcType) throws SQLException
  {
    ps.setString(parameterIndex, encrypt(parameter));
  }

  @Override
  public final ApiKeyToken getNullableResult(final ResultSet rs, final String columnName) throws SQLException {
    return decrypt(rs.getString(columnName));
  }

  @Override
  public final ApiKeyToken getNullableResult(final ResultSet rs, final int columnIndex) throws SQLException {
    return decrypt(rs.getString(columnIndex));
  }

  @Override
  public final ApiKeyToken getNullableResult(final CallableStatement cs, final int columnIndex) throws SQLException {
    return decrypt(cs.getString(columnIndex));
  }

  /**
   * Encrypt token using database cipher + Base64.
   */
  private String encrypt(final ApiKeyToken token) {
    // Use try-with-resources to ensure proper cleanup of sensitive data
    try {
      // Use NIO buffer to avoid copying contents into String
      CharBuffer charBuffer = token.getCharBuffer();
      ByteBuffer byteBuffer = UTF_8.encode(charBuffer);
      try {
        byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        
        // Use java.util.Base64 instead of Jackson's Base64Variant
        return Base64.getEncoder().encodeToString(cipher().encrypt(bytes));
      } finally {
        // Clear the byte buffer to remove sensitive data
        if (byteBuffer.hasArray()) {
          // Clear the backing array if accessible
          byte[] array = byteBuffer.array();
          for (int i = byteBuffer.arrayOffset(); i < byteBuffer.arrayOffset() + byteBuffer.capacity(); i++) {
            array[i] = 0;
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(STR."Failed to encrypt API key token: \{e.getMessage()}", e);
    }
  }

  /**
   * Decrypt token using Base64 + database cipher.
   */
  private ApiKeyToken decrypt(final String value) {
    if (value == null) {
      return null;
    }

    try {
      // Use java.util.Base64 instead of Jackson's Base64Variant
      byte[] bytes = cipher().decrypt(Base64.getDecoder().decode(value));
      
      // Use try-with-resources to ensure proper cleanup of sensitive data
      ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
      try {
        CharBuffer charBuffer = UTF_8.decode(byteBuffer);
        try {
          char[] chars = new char[charBuffer.remaining()];
          charBuffer.get(chars);
          return new ApiKeyToken(chars);
        } finally {
          // Clear the char buffer to remove sensitive data
          if (charBuffer.hasArray()) {
            char[] array = charBuffer.array();
            for (int i = charBuffer.arrayOffset(); i < charBuffer.arrayOffset() + charBuffer.capacity(); i++) {
              array[i] = 0;
            }
          }
        }
      } finally {
        // Clear the byte buffer to remove sensitive data
        if (byteBuffer.hasArray()) {
          byte[] array = byteBuffer.array();
          for (int i = byteBuffer.arrayOffset(); i < byteBuffer.arrayOffset() + byteBuffer.capacity(); i++) {
            array[i] = 0;
          }
        }
      }
    } catch (Exception e) {
      throw new RuntimeException(STR."Failed to decrypt API key token: \{e.getMessage()}", e);
    }
  }
}