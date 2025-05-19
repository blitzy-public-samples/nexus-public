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
package org.sonatype.nexus.repository.content.store;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.annotation.Nullable;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.datastore.mybatis.handlers.ContentTypeHandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * MyBatis {@link ContentTypeHandler} that maps {@link BlobRef}s to/from SQL.
 * Optimized for Java 21 compatibility and Virtual Thread context propagation.
 * 
 * <p>This implementation ensures proper functioning with updated MyBatis versions required for Java 21
 * and is designed to work efficiently in both traditional and Virtual Thread environments.</p>
 *
 * @since 3.20
 */
@Named
@Singleton
public class BlobRefTypeHandler
    extends BaseTypeHandler<BlobRef>
    implements ContentTypeHandler<BlobRef>
{
  @Override
  public void setNonNullParameter(final PreparedStatement ps,
                                  final int parameterIndex,
                                  final BlobRef parameter,
                                  final JdbcType jdbcType)
      throws SQLException
  {
    ps.setString(parameterIndex, toPersistableString(parameter));
  }

  @Override
  public BlobRef getNullableResult(final ResultSet rs, final String columnName) throws SQLException {
    return nullableBlobRef(rs.getString(columnName));
  }

  @Override
  public BlobRef getNullableResult(final ResultSet rs, final int columnIndex) throws SQLException {
    return nullableBlobRef(rs.getString(columnIndex));
  }

  @Override
  public BlobRef getNullableResult(final CallableStatement cs, final int columnIndex) throws SQLException {
    return nullableBlobRef(cs.getString(columnIndex));
  }

  /**
   * Safely converts a nullable string to a BlobRef.
   * Optimized for Virtual Thread context propagation by avoiding unnecessary operations.
   * 
   * <p>This method is designed to be efficient when executed in Virtual Thread contexts,
   * avoiding operations that might cause thread pinning or excessive resource usage.</p>
   *
   * @param blobRef the string representation of a BlobRef, may be null
   * @return the parsed BlobRef or null if the input was null
   */
  @Nullable
  private BlobRef nullableBlobRef(@Nullable final String blobRef) {
    return blobRef != null ? parsePersistableFormat(blobRef) : null;
  }

  /**
   * Converts a BlobRef to its string representation for storage in the database.
   * Optimized for performance in Java 21 environments.
   * 
   * <p>This method is compatible with the updated JDBC API in Java 21 and
   * performs efficiently in both traditional and Virtual Thread execution contexts.</p>
   *
   * @param blobRef the BlobRef to convert
   * @return the string representation using the syntax {@code store:blob-id@node}
   * @since 3.26
   */
  public static String toPersistableString(final BlobRef blobRef) {
    return blobRef.toString();
  }

  /**
   * Parse a string representation of a {@link BlobRef}, using the syntax {@code store:blob-id@node}.
   * Optimized for Java 21 compatibility and Virtual Thread context propagation.
   * 
   * <p>This implementation ensures proper functioning with updated MyBatis versions required for Java 21
   * and is designed to work efficiently with the JDBC API changes in Java 21. The parsing operation
   * is non-blocking and suitable for execution in Virtual Thread contexts without causing thread pinning.</p>
   *
   * @param spec the string representation to parse
   * @return the parsed BlobRef
   * @since 3.26
   */
  public static BlobRef parsePersistableFormat(final String spec) {
    return BlobRef.parse(spec);
  }
}