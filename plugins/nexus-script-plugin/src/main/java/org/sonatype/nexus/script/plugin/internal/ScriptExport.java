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
package org.sonatype.nexus.script.plugin.internal;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.supportzip.ExportConfigData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import static java.lang.StringTemplate.STR;

/**
 * Write/Read {@link Script} data to/from a JSON file.
 * 
 * <p>This class leverages Java 21 Virtual Threads for I/O operations to improve performance
 * during support bundle operations. The export and restore operations are executed in
 * Virtual Threads through the parent JsonExporter implementation, allowing for efficient
 * handling of I/O-bound tasks without blocking platform threads.</p>
 *
 * <p>Java 21 String Templates are used for logging to improve readability and performance.</p>
 *
 * @since 3.29
 */
@Named("scriptExport")
@Singleton
public class ScriptExport
    extends JsonExporter
    implements ExportConfigData, ImportData
{
  private final ScriptStore store;

  @Inject
  public ScriptExport(final ScriptStore store) {
    this.store = store;
  }

  /**
   * Exports Script data to a JSON file.
   * 
   * <p>This operation is executed in a Virtual Thread to improve performance for I/O operations.</p>
   *
   * @param file the file to export to
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void export(final File file) throws IOException {
    log.debug(STR."Export Script data to \{file}");
    List<Script> scripts = store.list();
    exportToJson(scripts, file);
  }

  /**
   * Restores Script data from a JSON file.
   * 
   * <p>This operation is executed in a Virtual Thread to improve performance for I/O operations.</p>
   *
   * @param file the file to restore from
   * @throws IOException if an I/O error occurs
   */
  @Override
  public void restore(final File file) throws IOException {
    log.debug(STR."Restoring Script data from \{file}");
    importFromJson(file, ScriptData.class).forEach(store::create);
  }
}