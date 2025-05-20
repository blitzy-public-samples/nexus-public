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
package org.sonatype.nexus.repository.rest.internal.resources;

import jakarta.annotation.Nullable;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.io.Hex;

import org.elasticsearch.index.query.QueryBuilder;

import static java.lang.Integer.parseInt;
import static java.lang.StringTemplate.STR;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.sonatype.nexus.common.hash.HashAlgorithm.MD5;
import static org.sonatype.nexus.repository.http.HttpStatus.NOT_ACCEPTABLE;

/**
 * @since 3.4
 */
@Singleton
@Named
public class TokenEncoder
    extends ComponentSupport
{
  public int decode(@Nullable final String continuationToken, final QueryBuilder query) {
    if (continuationToken == null) {
      return 0;
    }
    else {
      String decoded = new String(Hex.decode(continuationToken), UTF_8);
      
      return switch (decoded.split(":")) {
        case String[] decodedParts when decodedParts.length != 2 -> {
          throw new WebApplicationException(STR."Unable to parse token \{continuationToken}", NOT_ACCEPTABLE);
        }
        case String[] decodedParts when !decodedParts[1].equals(getHashCode(query)) -> {
          throw new WebApplicationException(
              STR."Continuation token \{continuationToken} does not match this query", NOT_ACCEPTABLE);
        }
        case String[] decodedParts -> parseInt(decodedParts[0]);
      };
    }
  }

  public String encode(final int lastFrom, final int pageSize, final QueryBuilder query) {
    int index = lastFrom + pageSize;
    return Hex.encode(STR."\{index}:\{getHashCode(query)}".getBytes(UTF_8));
  }

  private String getHashCode(final QueryBuilder query) {
    // Using Java 21's enhanced cryptographic capabilities through the standard MD5 function
    return MD5.function().hashString(query.toString(), UTF_8).toString();
  }
}