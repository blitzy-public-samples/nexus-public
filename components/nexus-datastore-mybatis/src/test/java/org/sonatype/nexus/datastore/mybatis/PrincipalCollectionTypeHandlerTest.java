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
package org.sonatype.nexus.datastore.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.sonatype.nexus.crypto.LegacyCipherFactory.PbeCipher;
import org.sonatype.nexus.datastore.mybatis.handlers.PrincipalCollectionTypeHandler;

import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.apache.commons.lang.SerializationUtils.serialize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrincipalCollectionTypeHandlerTest
{
  private static final byte[] PRINCIPAL_COLLECTION_BYTES = {8, 5, 11, 110};

  private static final int AN_INDEX = 0;

  private static final String COLUMN = "COLUMN";

  private static SimplePrincipalCollection principalCollection;

  private static byte[] principalCollectionBytes;

  @Mock
  private ResultSet resultSet;

  @Mock
  private CallableStatement callableStatement;

  @Mock
  private PbeCipher databaseCipher;

  @Mock
  private PreparedStatement preparedStatement;

  private PrincipalCollectionTypeHandler principalCollectionTypeHandler;

  @BeforeAll
  static void setupClass() {
    principalCollection = new SimplePrincipalCollection("p1", "r1");
    principalCollectionBytes = serialize(principalCollection);
  }

  @BeforeEach
  void setup() {
    principalCollectionTypeHandler = new PrincipalCollectionTypeHandler();
    when(databaseCipher.decrypt(principalCollectionBytes)).thenReturn(principalCollectionBytes);
    ((CipherAwareTypeHandler<?>) principalCollectionTypeHandler).setCipher(databaseCipher);
  }

  @Test
  void shouldSetEncryptedPrincipalCollectionBytes() throws Exception {
    when(databaseCipher.encrypt(any(byte[].class))).thenReturn(PRINCIPAL_COLLECTION_BYTES);

    principalCollectionTypeHandler.setNonNullParameter(preparedStatement,
        AN_INDEX,
        new SimplePrincipalCollection("p1", "r1"), null);

    verify(preparedStatement).setBytes(AN_INDEX, PRINCIPAL_COLLECTION_BYTES);
  }

  @Test
  void shouldBeNullWhenResultSetReturnsNullForColumnIndex() throws Exception {

    final PrincipalCollection result = principalCollectionTypeHandler.getNullableResult(resultSet, AN_INDEX);

    assertNull(result);
  }

  @Test
  void shouldBePrincipalCollectionWhenResultSetReturnsBytesForColumnIndex() throws Exception {
    when(resultSet.getBytes(AN_INDEX)).thenReturn(principalCollectionBytes);

    final PrincipalCollection result = principalCollectionTypeHandler.getNullableResult(resultSet, AN_INDEX);

    assertEquals(principalCollection, result);
  }

  @Test
  void shouldBeNullWhenResultSetReturnsNullForColumnName() throws Exception {

    final PrincipalCollection result = principalCollectionTypeHandler.getNullableResult(resultSet, COLUMN);

    assertNull(result);
  }

  @Test
  void shouldBeNullWhenResultSetReturnsBytesForColumnName() throws Exception {
    when(resultSet.getBytes(COLUMN)).thenReturn(principalCollectionBytes);

    final PrincipalCollection result = principalCollectionTypeHandler.getNullableResult(resultSet, COLUMN);

    assertEquals(principalCollection, result);
  }

  @Test
  void shouldBeNullWhenCallableStatementReturnsNull() throws Exception {

    final PrincipalCollection result = principalCollectionTypeHandler.getNullableResult(callableStatement, AN_INDEX);

    assertNull(result);
  }

  @Test
  void shouldBePrincipalCollectionWhenCallableStatementReturnsBytes() throws Exception {
    when(callableStatement.getBytes(AN_INDEX)).thenReturn(principalCollectionBytes);

    final PrincipalCollection result = principalCollectionTypeHandler.getNullableResult(callableStatement, AN_INDEX);

    assertEquals(principalCollection, result);
  }
}
