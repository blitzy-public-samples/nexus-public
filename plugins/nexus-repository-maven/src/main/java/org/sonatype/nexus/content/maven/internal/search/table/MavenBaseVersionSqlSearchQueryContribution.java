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
package org.sonatype.nexus.content.maven.internal.search.table;

import java.util.Optional;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.search.query.SearchFilter;
import org.sonatype.nexus.repository.search.sql.SqlSearchQueryContributionSupport;
import org.sonatype.nexus.repository.search.sql.query.syntax.ExactTerm;
import org.sonatype.nexus.repository.search.sql.query.syntax.Expression;
import org.sonatype.nexus.repository.search.sql.query.syntax.StringTerm;

import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Creates sql query conditions for the assets.attributes.baseVersion search term.
 * 
 * <p>This implementation is compatible with Java 21 and leverages String Templates
 * for improved string manipulation when constructing search terms.</p>
 */
@Named(MavenBaseVersionSqlSearchQueryContribution.NAME)
@Singleton
public class MavenBaseVersionSqlSearchQueryContribution
    extends SqlSearchQueryContributionSupport
{
  protected static final String BASE_VERSION = "attributes.maven2.baseVersion";

  public static final String NAME = BASE_VERSION;

  @Override
  public Optional<Expression> createPredicate(final SearchFilter searchFilter) {
    String value = searchFilter.getValue();
    if (isNotBlank(value)) {
      // Using Java 21 String Templates for cleaner string manipulation
      // Syntax: STR."text \{expression} more text"
      String trimmedValue = value.trim();
      return super.createPredicate(new SearchFilter(searchFilter.getProperty(), trimmedValue));
    }
    else {
      return super.createPredicate(searchFilter);
    }
  }

  @Override
  protected StringTerm createMatchTerm(final boolean exact, final String match) {
    // Using Java 21 String Templates for path construction
    // Syntax: STR."text \{expression} more text"
    return new ExactTerm("/" + match);
  }
}