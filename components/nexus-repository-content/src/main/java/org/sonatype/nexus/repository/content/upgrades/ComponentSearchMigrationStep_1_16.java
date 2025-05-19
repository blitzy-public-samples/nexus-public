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

import java.sql.Connection;
import java.util.Optional;
import javax.inject.Named;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This migration step was deleted and is no longer available since the component_search table was deleted in a later step
 */
@Named
public class ComponentSearchMigrationStep_1_16
    implements DatabaseMigrationStep
{
  private static final Logger log = LoggerFactory.getLogger(ComponentSearchMigrationStep_1_16.class);

  @Override
  public Optional<String> version() {
    String version = "1.16";
    log.debug(STR."Reporting migration step version: \{version}");
    return Optional.of(version);
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    log.info(STR."Skipping migration step \{version().orElse("unknown")} as component_search table was deleted in a later step");
  }
}