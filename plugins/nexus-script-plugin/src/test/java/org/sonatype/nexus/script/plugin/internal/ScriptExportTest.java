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
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests validity of Serialization/Deserialization {@link Script} by {@link ScriptExport}
 */
@ExtendWith(MockitoExtension.class)
public class ScriptExportTest
{
  private final JsonExporter jsonExporter = new JsonExporter();

  private File jsonFile;

  @BeforeEach
  public void setUp() throws IOException {
    jsonFile = File.createTempFile("SamlUser", ".json");
  }

  @AfterEach
  public void tearDown() {
    jsonFile.delete();
  }

  @Test
  public void shouldExportImportToJson() throws Exception {
    List<Script> scripts = Arrays.asList(
        createScript("script_1"),
        createScript("script_2"));

    ScriptStore store = mock(ScriptStore.class);
    when(store.list()).thenReturn(scripts);

    ScriptExport exporter = new ScriptExport(store);
    exporter.export(jsonFile);
    List<ScriptData> importedData = jsonExporter.importFromJson(jsonFile, ScriptData.class);

    assertEquals(2, importedData.size());
    importedData.forEach(data -> {
      assertTrue(scripts.get(0).getName().equals(data.getName()) || 
                scripts.get(1).getName().equals(data.getName()));
    });
    importedData.forEach(data -> assertEquals("script", data.getType()));
    importedData.forEach(data -> assertEquals("log.info('world')", data.getContent()));
  }
  
  @Test
  public void shouldHandleIOOperationsWithVirtualThreads() throws Exception {
    // Number of virtual threads to create
    int threadCount = 10;
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<Path> tempFiles = new ArrayList<>();
    List<Exception> exceptions = new ArrayList<>();
    
    // Create and start virtual threads for I/O operations
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.startVirtualThread(() -> {
        try {
          // Create a temporary file for this thread
          Path tempFile = Files.createTempFile("vthread-test-" + threadId, ".txt");
          tempFiles.add(tempFile);
          
          // Write some data to the file
          try (FileWriter writer = new FileWriter(tempFile.toFile())) {
            writer.write("Virtual thread " + threadId + " writing to file\n");
            writer.write("This demonstrates I/O operations with virtual threads\n");
            // Simulate some processing time
            Thread.sleep(50);
          }
          
          // Read the data back to verify
          String content = Files.readString(tempFile);
          assertTrue(content.contains("Virtual thread " + threadId));
          assertTrue(content.contains("demonstrates I/O operations"));
          
        } catch (Exception e) {
          synchronized (exceptions) {
            exceptions.add(e);
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete (with timeout)
    boolean completed = latch.await(5, TimeUnit.SECONDS);
    
    // Cleanup temp files
    for (Path file : tempFiles) {
      Files.deleteIfExists(file);
    }
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all virtual threads completed in time");
    assertTrue(exceptions.isEmpty(), "Exceptions occurred during virtual thread execution: " + exceptions);
    assertEquals(threadCount, tempFiles.size(), "Not all threads created temp files");
  }

  private Script createScript(final String name) {
    ScriptData script = new ScriptData();
    script.setName(name);
    script.setType("script");
    script.setContent("log.info('world')");

    return script;
  }
}

