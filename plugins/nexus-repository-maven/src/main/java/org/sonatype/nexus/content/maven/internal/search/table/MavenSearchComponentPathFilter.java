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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.repository.content.utils.SearchComponentPathFilter;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;

import static org.sonatype.nexus.content.maven.internal.search.table.MavenSearchComponentPathFilter.MavenType.getMavenTypes;

/**
 * Maven implementation of {@link SearchComponentPathFilter} that filters paths based on Maven artifact types.
 * 
 * @since 3.38
 */
@Named(Maven2Format.NAME)
@Singleton
public class MavenSearchComponentPathFilter
    implements SearchComponentPathFilter
{
  /**
   * Enumeration of Maven artifact types used for filtering.
   */
  enum MavenType
  {
    POM(".pom"),
    WAR(".war"),
    JAR(".jar"),
    EAR(".ear"),
    AAR(".aar"),
    ZIP(".zip"),
    TARGZ(".tar.gz");

    private final String mavenType;

    MavenType(final String mavenType) {
      this.mavenType = mavenType;
    }

    private String getMavenType() {
      return mavenType;
    }

    /**
     * Returns an unmodifiable list of all Maven types defined in this enum.
     * 
     * @return unmodifiable list of Maven types
     */
    static List<String> getMavenTypes() {
      return Arrays.stream(MavenType.values())
          .map(MavenType::getMavenType)
          .collect(Collectors.toUnmodifiableList());
    }
  }

  @Override
  public boolean shouldFilterPathExtension(final String path) {
    return getMavenTypes().stream().noneMatch(path::endsWith);
  }
}