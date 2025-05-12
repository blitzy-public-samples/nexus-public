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
package org.sonatype.nexus.repository.apt.internal.snapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.sonatype.nexus.repository.apt.internal.debian.ControlFile;
import org.sonatype.nexus.repository.apt.internal.debian.Release;

/**
 * Filtered implementation of {@link SnapshotComponentSelector} that selects components
 * based on configuration settings.
 *
 * @since 3.17
 * @Java21 Updated to use Java 21 pattern matching for Optional
 */
public class FilteredSnapshotComponentSelector
    implements SnapshotComponentSelector
{
  private final ControlFile settings;

  /**
   * Constructor with required settings.
   *
   * @param settings the control file containing configuration settings
   */
  public FilteredSnapshotComponentSelector(final ControlFile settings) {
    this.settings = settings;
  }

  @Override
  public List<String> getArchitectures(final Release release) {
    // Using Java 21 pattern matching for Optional
    return switch (settings.getField("Architectures")
        .map(s -> s.listValue())
        .map(l -> new HashSet<>(l))) {
      case Optional<Set<String>> settingsArchitectures when settingsArchitectures.isPresent() -> {
        Set<String> releaseArchitectures = new HashSet<>(release.getArchitectures());
        releaseArchitectures.retainAll(settingsArchitectures.get());
        yield new ArrayList<>(releaseArchitectures);
      }
      default -> release.getArchitectures();
    };
  }

  @Override
  public List<String> getComponents(final Release release) {
    // Using Java 21 pattern matching for Optional
    return switch (settings.getField("Components")
        .map(s -> s.listValue())
        .map(l -> new HashSet<>(l))) {
      case Optional<Set<String>> settingsComponents when settingsComponents.isPresent() -> {
        Set<String> releaseComponents = new HashSet<>(release.getComponents());
        releaseComponents.retainAll(settingsComponents.get());
        yield new ArrayList<>(releaseComponents);
      }
      default -> release.getComponents();
    };
  }
}