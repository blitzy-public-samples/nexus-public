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
package org.sonatype.nexus.repository.search.sql;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.inject.Inject;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.repository.rest.SearchMapping;
import org.sonatype.nexus.repository.rest.sql.SearchField;
import org.sonatype.nexus.repository.search.query.SearchFilter;
import org.sonatype.nexus.repository.search.sql.query.syntax.ExactTerm;
import org.sonatype.nexus.repository.search.sql.query.syntax.Expression;
import org.sonatype.nexus.repository.search.sql.query.syntax.LenientTerm;
import org.sonatype.nexus.repository.search.sql.query.syntax.SqlClause;
import org.sonatype.nexus.repository.search.sql.query.syntax.SqlPredicate;
import org.sonatype.nexus.repository.search.sql.query.syntax.StringTerm;
import org.sonatype.nexus.repository.search.sql.query.syntax.TermCollection;
import org.sonatype.nexus.repository.search.sql.query.syntax.WildcardTerm;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.removeEnd;
import static org.apache.commons.lang3.StringUtils.removeStart;
import static org.sonatype.nexus.repository.search.sql.query.syntax.Operand.EQ;
import static org.sonatype.nexus.repository.search.sql.query.syntax.Operand.OR;

/**
 * Base implementation for {@link SqlSearchQueryContribution}
 *
 * @since 3.38
 */
public abstract class SqlSearchQueryContributionSupport
    extends SqlSearchValidationSupport
    implements SqlSearchQueryContribution
{
  private static final String QUOTE = "\"";

  protected SearchMappingService mappingService;

  @Inject
  public void init(final SearchMappingService mappingService) {
    this.mappingService = checkNotNull(mappingService);
  }

  @Override
  public Optional<Expression> createPredicate(@Nullable final SearchFilter filter) {
    Optional<SearchField> field = getField(filter);

    log.debug("Mapping for {} is {}", filter, field);

    // Use pattern matching to handle filter and field validation
    if (filter == null || field.isEmpty()) {
      return Optional.empty();
    }

    boolean exact = isExact(filter);
    
    // Use pattern matching for switch to handle different filter values
    return switch (filter.getValue()) {
      case null -> Optional.empty();
      case String value -> {
        String trimmedValue = value.trim();
        var expressions = split(trimmedValue)
            .map(tokens -> tokenize(exact, tokens))
            .map(TermCollection::create)
            .map(terms -> new SqlPredicate(EQ, field.get(), terms))
            .collect(Collectors.toList());
        yield Optional.of(SqlClause.create(OR, expressions));
      }
    };
  }

  /**
   * Split the query by spaces outside of quotes. i.e. {@code "nexus*core foo*" -> ["nexus*core", "foo*"]}
   *
   * @param value a search filter
   */
  protected Stream<String> split(final String value) {
    if (isBlank(value)) {
      return Stream.of("");
    }

    Set<String> tokens = new LinkedHashSet<>();
    char[] chars = value.toCharArray();

    StringBuilder token = new StringBuilder();
    boolean quoted = false;
    for (int i=0; i<chars.length; i++) {
      char c = chars[i];
      if (c == '\\') {
        token.append(c);
        if (i + 1 < chars.length) {
          token.append(chars[++i]);
        }
      }
      else if (c == '"') {
        if (quoted) {
          tokens.add(token.toString().trim());
          token = new StringBuilder();
          quoted = false;
        }
        else {
          quoted = true;
        }
      }
      else if (!quoted && c == ' ') {
        tokens.add(token.toString().trim());
        token = new StringBuilder();
      }
      else {
        token.append(c);
      }
    }

    if (Strings2.notBlank(token.toString())) {
      tokens.add(token.toString().trim());
    }

    return getValidTokens(tokens).stream();
  }

  /**
   * Tokenize terms query strings into StringTerm instances
   *
   * @param exact indicates whether the associated {@link SearchMapping} indicated exact matching
   * @param value a string from {@link #split} to tokenize
   */
  protected Collection<StringTerm> tokenize(final boolean exact, final String value) {
    if (isBlank(value)) {
      return Collections.singleton(new ExactTerm(""));
    }

    // Define a record to represent the current parsing state for pattern matching
    record TokenState(char c, boolean quoted, boolean terminated, boolean terminalWildcard) {}
    
    Set<StringTerm> tokens = new LinkedHashSet<>();
    char[] chars = value.toCharArray();

    StringBuilder token = new StringBuilder();
    boolean quoted = false;
    boolean terminated = false;
    boolean terminalWildcard = false;
    
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      TokenState state = new TokenState(c, quoted, terminated, terminalWildcard);
      
      // Use pattern matching for switch to handle different character cases
      switch (state) {
        case TokenState('\\', _, _, _) when i + 1 < chars.length -> {
          token.append(chars[++i]);
        }
        case TokenState('"', true, _, _) -> {
          doCreateMatchTerm(exact, token).ifPresent(tokens::add);
          token = new StringBuilder();
          quoted = false;
        }
        case TokenState('"', false, _, _) -> {
          quoted = true;
        }
        case TokenState(char c, false, _, _) when c == ' ' || c == '*' || c == '?' -> {
          terminalWildcard = c == '*' || c == '?';
          terminated = true;
        }
        case TokenState(_, _, true, _) -> {
          create(exact, terminalWildcard, token.toString().trim()).ifPresent(tokens::add);
          terminalWildcard = terminated = false;
          token = new StringBuilder();
          token.append(c);
        }
        default -> {
          token.append(c);
        }
      }
    }

    create(exact, terminalWildcard, token.toString().trim()).ifPresent(tokens::add);

    return tokens;
  }

  private Optional<StringTerm> create(final boolean exact, final boolean terminalWildcard, final String token) {
    // Use pattern matching for switch to handle different term types based on terminalWildcard
    return switch (terminalWildcard) {
      case false -> doCreateMatchTerm(exact, token);
      case true -> Optional.of(new WildcardTerm(token));
    };
  }

  private Optional<StringTerm> doCreateMatchTerm(final boolean exact, final CharSequence value) {
    // Use pattern matching for Optional to simplify the chain of operations
    String trimmedValue = value != null ? value.toString().trim() : null;
    
    return switch (trimmedValue) {
      case null -> Optional.empty();
      case String s when Strings2.isBlank(s) -> Optional.empty();
      case String s -> Optional.of(createMatchTerm(exact, s));
    };
  }

  /**
   * Create a {@link StringTerm} from the value, and the {@link SearchMapping}'s indication whether the filter should be
   * treated as exact.
   *
   * @param exact indicates whether the associated {@link SearchMapping} indicated exact matching
   * @param value the term
   */
  protected StringTerm createMatchTerm(final boolean exact, final String value) {
    // Use switch expression for more concise conditional logic
    return switch (exact) {
      case true -> new ExactTerm(value);
      case false -> new LenientTerm(value);
    };
  }

  protected static String maybeTrimQuotes(String term) {
    term = removeEnd(term, QUOTE);
    term = removeStart(term, QUOTE);
    return term;
  }

  protected boolean isExact(@Nullable final SearchFilter filter) {
    // Use pattern matching for Optional to simplify the chain of operations
    return switch (filter) {
      case null -> false;
      case SearchFilter sf -> {
        String property = sf.getProperty();
        yield property != null ? mappingService.isExactMatch(property) : false;
      }
    };
  }

  protected Optional<SearchField> getField(@Nullable final SearchFilter filter) {
    // Use pattern matching for Optional to simplify the chain of operations
    return switch (filter) {
      case null -> Optional.empty();
      case SearchFilter sf -> {
        String property = sf.getProperty();
        yield property != null ? mappingService.getSearchField(property) : Optional.empty();
      }
    };
  }
}