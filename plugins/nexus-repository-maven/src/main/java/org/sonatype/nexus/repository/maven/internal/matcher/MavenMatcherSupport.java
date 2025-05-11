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
package org.sonatype.nexus.repository.maven.internal.matcher;

import java.util.function.Predicate;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.maven.MavenPath;
import org.sonatype.nexus.repository.maven.MavenPath.HashType;
import org.sonatype.nexus.repository.maven.MavenPathParser;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Matcher;

import static java.util.Objects.requireNonNull;

/**
 * Matcher support for Maven use cases.
 * 
 * Updated for Java 21 with pattern matching for switch expressions and improved predicate handling.
 *
 * @since 3.0
 */
public class MavenMatcherSupport
    extends ComponentSupport
    implements Matcher
{
  /**
   * Returns a {@link Predicate} that will match for given path whenever passed in predicate matches, plus, for Maven2
   * layout defined extensions like ".sha1" and ".md5".
   * 
   * Leverages Java 21 pattern matching for more concise code.
   */
  public static Predicate<String> withHashes(final Predicate<String> predicate) {
    return (String input) ->
    {
      // Extract main path by removing hash extension if present
      String mainPath = switch (input) {
        case String path when path.endsWith("." + HashType.SHA1.getExt()) -> 
            path.substring(0, path.length() - (HashType.SHA1.getExt().length() + 1));
        case String path when path.endsWith("." + HashType.MD5.getExt()) -> 
            path.substring(0, path.length() - (HashType.MD5.getExt().length() + 1));
        default -> input;
      };
      return predicate.test(mainPath);
    };
  }

  private final MavenPathParser mavenPathParser;

  private final Predicate<String> predicate;

  public MavenMatcherSupport(final MavenPathParser mavenPathParser, final Predicate<String> predicate) {
    this.mavenPathParser = requireNonNull(mavenPathParser);
    this.predicate = requireNonNull(predicate);
  }

  /**
   * Matches the request path against the predicate and sets the MavenPath attribute if matched.
   * 
   * @param context The request context to match against
   * @return true if the path matches the predicate, false otherwise
   */
  @Override
  public boolean matches(final Context context) {
    final String path = context.getRequest().getPath();
    // Using pattern matching to simplify the code flow
    return switch (predicate.test(path)) {
      case true -> {
        final MavenPath mavenPath = mavenPathParser.parsePath(path);
        context.getAttributes().set(MavenPath.class, mavenPath);
        yield true;
      }
      case false -> false;
    };
  }
}