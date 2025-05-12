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
import java.util.stream.Stream;
import java.util.stream.Collectors;

import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization {@link Script} by {@link ScriptExport}
 *
 * @since 3.next
 * @see ScriptExport
 * 
 * Updated for Java 21 compatibility using JUnit Jupiter 5.10.1.
 * Uses modern Stream API and collectors instead of deprecated Arrays.asList().
 * Implements JUnit 5 lifecycle annotations for test setup and teardown.
 */
public class ScriptExportTest
{
  private final JsonExporter jsonExporter = new JsonExporter();

  private File jsonFile;

  @BeforeEach
  public void setup() throws IOException {
    jsonFile = File.createTempFile("SamlUser", ".json");
  }

  @AfterEach
  public void tearDown() {
    if (jsonFile != null && jsonFile.exists()) {
      jsonFile.delete();
    }
  }

  @Test
  public void testExportImportToJson() throws Exception {
    List<Script> scripts = Stream.of(
        createScript("script_1"),
        createScript("script_2"))
        .collect(Collectors.toList());

    ScriptStore store = mock(ScriptStore.class);
    when(store.list()).thenReturn(scripts);

    ScriptExport exporter = new ScriptExport(store);
    exporter.export(jsonFile);
    List<ScriptData> importedData = jsonExporter.importFromJson(jsonFile, ScriptData.class);

    assertThat(importedData.size(), is(2));
    importedData.forEach(data -> assertThat(data.getName(), anyOf(
        is(scripts.get(0).getName()),
        is(scripts.get(1).getName()))));
    importedData.forEach(data -> assertThat(data.getType(), is("script")));
    importedData.forEach(data -> assertThat(data.getContent(), is("log.info('world')")));
  }

  private Script createScript(final String name) {
    ScriptData script = new ScriptData();
    script.setName(name);
    script.setType("script");
    script.setContent("log.info('world')");

    return script;
  }
}