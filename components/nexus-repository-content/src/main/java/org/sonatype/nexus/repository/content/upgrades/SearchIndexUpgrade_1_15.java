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
package org.sonatype.nexus.repository.content.upgrades;

import java.util.Optional;

import javax.inject.Named;
import javax.inject.Singleton;

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.repository.content.search.upgrade.SearchIndexUpgrade;

/**
 * Re-index search for all formats to store external id instead of internal id.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class SearchIndexUpgrade_1_15
    extends SearchIndexUpgrade
{
  /**
   * Returns the version of this upgrade using Java 21 String Templates for consistent formatting.
   *
   * @return the version as an Optional<String>
   */
  @Override
  public Optional<String> version() {
    // Using Java 21 String Templates for consistent version formatting
    String versionNumber = STR."\{1}.\{15}";
    return Optional.of(versionNumber);
  }
}