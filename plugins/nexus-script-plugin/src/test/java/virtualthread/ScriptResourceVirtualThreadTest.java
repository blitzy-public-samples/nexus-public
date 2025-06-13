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
package virtualthread;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.script.ScriptService;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;
import org.sonatype.nexus.script.ScriptResultXO;
import org.sonatype.nexus.script.ScriptRunEvent;
import org.sonatype.nexus.script.ScriptXO;
import org.sonatype.nexus.script.plugin.internal.ScriptingDisabledException;
import org.sonatype.nexus.script.plugin.internal.rest.ScriptResource;
import org.sonatype.nexus.script.plugin.internal.security.ScriptPermission;
import org.sonatype.nexus.security.SecurityHelper;

/**
 * Tests the {@link ScriptResource} REST API implementation using Java 21 Virtual Threads to verify that
 * REST operations (browse, read, add, edit, delete, run) work correctly in a virtual thread environment.
 * 
 * This test ensures that the REST API remains reliable when executed with virtual threads, which is critical
 * for maintaining performance during high-concurrency scenarios.
 */
public class ScriptResourceVirtualThreadTest
    extends TestSupport
{
  private ScriptManager scriptManager;
  private SecurityHelper securityHelper;
  private ScriptService scriptService;
  private EventManager eventManager;
  private ScriptResource underTest;

  @BeforeEach
  public void setup() {
    scriptManager = mock(ScriptManager.class);
    securityHelper = mock(SecurityHelper.class);
    scriptService = mock(ScriptService.class);
    eventManager = mock(EventManager.class);

    underTest = new ScriptResource(scriptManager, securityHelper, scriptService, eventManager);
  }

  /**
   * Tests that the browse operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testBrowseInVirtualThread() throws Exception {
    // Setup test data
    List<Script> scripts = new ArrayList<>();
    Script script1 = mock(Script.class);
    when(script1.getName()).thenReturn("script1");
    when(script1.getContent()).thenReturn("println 'Hello'");
    when(script1.getType()).thenReturn("groovy");
    scripts.add(script1);

    Script script2 = mock(Script.class);
    when(script2.getName()).thenReturn("script2");
    when(script2.getContent()).thenReturn("println 'World'");
    when(script2.getType()).thenReturn("groovy");
    scripts.add(script2);

    when(scriptManager.browse()).thenReturn(scripts);

    // Execute in virtual thread
    AtomicReference<List<ScriptXO>> result = new AtomicReference<>();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        FutureTask<List<ScriptXO>> task = new FutureTask<>(() -> underTest.browse());
        Thread.startVirtualThread(task);
        result.set(task.get());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    future.get(); // Wait for completion

    // Verify results
    List<ScriptXO> scriptXOs = result.get();
    assertThat(scriptXOs, notNullValue());
    assertThat(scriptXOs.size(), is(2));
    assertThat(scriptXOs.get(0).getName(), is("script1"));
    assertThat(scriptXOs.get(0).getContent(), is("println 'Hello'"));
    assertThat(scriptXOs.get(0).getType(), is("groovy"));
    assertThat(scriptXOs.get(1).getName(), is("script2"));
    assertThat(scriptXOs.get(1).getContent(), is("println 'World'"));
    assertThat(scriptXOs.get(1).getType(), is("groovy"));
  }

  /**
   * Tests that the read operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testReadInVirtualThread() throws Exception {
    // Setup test data
    String scriptName = "testScript";
    Script script = mock(Script.class);
    when(script.getName()).thenReturn(scriptName);
    when(script.getContent()).thenReturn("println 'Test'");
    when(script.getType()).thenReturn("groovy");
    when(scriptManager.get(scriptName)).thenReturn(script);

    // Execute in virtual thread
    AtomicReference<ScriptXO> result = new AtomicReference<>();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        FutureTask<ScriptXO> task = new FutureTask<>(() -> underTest.read(scriptName));
        Thread.startVirtualThread(task);
        result.set(task.get());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    future.get(); // Wait for completion

    // Verify results
    ScriptXO scriptXO = result.get();
    assertThat(scriptXO, notNullValue());
    assertThat(scriptXO.getName(), is(scriptName));
    assertThat(scriptXO.getContent(), is("println 'Test'"));
    assertThat(scriptXO.getType(), is("groovy"));

    // Verify security check was performed
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
  }

  /**
   * Tests that the read operation correctly handles not found exceptions when executed in a virtual thread.
   */
  @Test
  public void testReadNotFoundInVirtualThread() throws Exception {
    // Setup test data
    String scriptName = "nonExistentScript";
    when(scriptManager.get(scriptName)).thenReturn(null);

    // Execute in virtual thread
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        Thread.startVirtualThread(() -> underTest.read(scriptName)).join();
      }
      catch (Exception e) {
        exception.set(e);
      }
    });

    future.get(); // Wait for completion

    // Verify exception
    assertThat(exception.get(), notNullValue());
    assertThat(exception.get().getCause() instanceof NotFoundException, is(true));
  }

  /**
   * Tests that the add operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testAddInVirtualThread() throws Exception {
    // Setup test data
    ScriptXO scriptXO = new ScriptXO("newScript", "println 'New'", "groovy");

    // Execute in virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        Thread.startVirtualThread(() -> {
          underTest.add(scriptXO);
        }).join();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    future.get(); // Wait for completion

    // Verify script was created
    verify(scriptManager).create(scriptXO.getName(), scriptXO.getContent(), scriptXO.getType());
  }

  /**
   * Tests that the add operation correctly handles scripting disabled exceptions when executed in a virtual thread.
   */
  @Test
  public void testAddScriptingDisabledInVirtualThread() throws Exception {
    // Setup test data
    ScriptXO scriptXO = new ScriptXO("newScript", "println 'New'", "groovy");
    doThrow(new ScriptingDisabledException("Scripting is disabled"))
        .when(scriptManager).create(anyString(), anyString(), anyString());

    // Execute in virtual thread
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        Thread.startVirtualThread(() -> {
          underTest.add(scriptXO);
        }).join();
      }
      catch (Exception e) {
        exception.set(e);
      }
    });

    future.get(); // Wait for completion

    // Verify exception
    assertThat(exception.get(), notNullValue());
    assertThat(exception.get().getCause() instanceof WebApplicationException, is(true));
  }

  /**
   * Tests that the edit operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testEditInVirtualThread() throws Exception {
    // Setup test data
    String scriptName = "existingScript";
    ScriptXO scriptXO = new ScriptXO(scriptName, "println 'Updated'", "groovy");
    Script script = mock(Script.class);
    when(script.getName()).thenReturn(scriptName);
    when(scriptManager.get(scriptName)).thenReturn(script);

    // Execute in virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        Thread.startVirtualThread(() -> {
          underTest.edit(scriptName, scriptXO);
        }).join();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    future.get(); // Wait for completion

    // Verify script was updated
    verify(scriptManager).update(scriptName, scriptXO.getContent());
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
  }

  /**
   * Tests that the delete operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testDeleteInVirtualThread() throws Exception {
    // Setup test data
    String scriptName = "scriptToDelete";
    Script script = mock(Script.class);
    when(script.getName()).thenReturn(scriptName);
    when(scriptManager.get(scriptName)).thenReturn(script);

    // Execute in virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        Thread.startVirtualThread(() -> {
          underTest.delete(scriptName);
        }).join();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    future.get(); // Wait for completion

    // Verify script was deleted
    verify(scriptManager).delete(scriptName);
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));
  }

  /**
   * Tests that the run operation works correctly when executed in a virtual thread.
   */
  @Test
  public void testRunInVirtualThread() throws Exception {
    // Setup test data
    String scriptName = "scriptToRun";
    String scriptArgs = "arg1 arg2";
    String scriptResult = "Script execution result";
    Script script = mock(Script.class);
    when(script.getName()).thenReturn(scriptName);
    when(script.getContent()).thenReturn("println 'Running'");
    when(script.getType()).thenReturn("groovy");
    when(scriptManager.get(scriptName)).thenReturn(script);
    when(scriptService.eval(eq(script.getType()), eq(script.getContent()), any(Map.class))).thenReturn(scriptResult);

    // Execute in virtual thread
    AtomicReference<ScriptResultXO> result = new AtomicReference<>();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        FutureTask<ScriptResultXO> task = new FutureTask<>(() -> underTest.run(scriptName, scriptArgs));
        Thread.startVirtualThread(task);
        result.set(task.get());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    future.get(); // Wait for completion

    // Verify results
    ScriptResultXO scriptResultXO = result.get();
    assertThat(scriptResultXO, notNullValue());
    assertThat(scriptResultXO.getName(), is(scriptName));
    assertThat(scriptResultXO.getResult(), is(scriptResult));

    // Verify security check was performed
    verify(securityHelper).ensurePermitted(any(ScriptPermission.class));

    // Verify event was published
    ArgumentCaptor<ScriptRunEvent> eventCaptor = ArgumentCaptor.forClass(ScriptRunEvent.class);
    verify(eventManager).post(eventCaptor.capture());
    assertThat(eventCaptor.getValue().getScript(), is(script));
  }

  /**
   * Tests that the run operation correctly handles script execution exceptions when executed in a virtual thread.
   */
  @Test
  public void testRunExceptionInVirtualThread() throws Exception {
    // Setup test data
    String scriptName = "scriptWithError";
    String scriptArgs = "arg1 arg2";
    Script script = mock(Script.class);
    when(script.getName()).thenReturn(scriptName);
    when(script.getContent()).thenReturn("throw new Exception('Script error')");
    when(script.getType()).thenReturn("groovy");
    when(scriptManager.get(scriptName)).thenReturn(script);
    when(scriptService.eval(eq(script.getType()), eq(script.getContent()), any(Map.class)))
        .thenThrow(new Exception("Script execution failed"));

    // Execute in virtual thread
    AtomicReference<Throwable> exception = new AtomicReference<>();
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        Thread.startVirtualThread(() -> underTest.run(scriptName, scriptArgs)).join();
      }
      catch (Exception e) {
        exception.set(e);
      }
    });

    future.get(); // Wait for completion

    // Verify exception
    assertThat(exception.get(), notNullValue());
    assertThat(exception.get().getCause() instanceof WebApplicationException, is(true));

    // Verify event was not published
    verify(eventManager, never()).post(any(ScriptRunEvent.class));
  }

  /**
   * Tests that multiple concurrent operations can be executed in virtual threads without issues.
   */
  @Test
  public void testConcurrentOperationsInVirtualThreads() throws Exception {
    // Setup test data
    String scriptName = "concurrentScript";
    Script script = mock(Script.class);
    when(script.getName()).thenReturn(scriptName);
    when(script.getContent()).thenReturn("println 'Concurrent'");
    when(script.getType()).thenReturn("groovy");
    when(scriptManager.get(scriptName)).thenReturn(script);
    when(scriptService.eval(eq(script.getType()), eq(script.getContent()), any(Map.class)))
        .thenReturn("Concurrent result");

    List<Script> scripts = Collections.singletonList(script);
    when(scriptManager.browse()).thenReturn(scripts);

    // Execute multiple operations concurrently in virtual threads
    CompletableFuture<List<ScriptXO>> browseFuture = CompletableFuture.supplyAsync(() -> {
      try {
        FutureTask<List<ScriptXO>> task = new FutureTask<>(() -> underTest.browse());
        Thread.startVirtualThread(task);
        return task.get();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    CompletableFuture<ScriptXO> readFuture = CompletableFuture.supplyAsync(() -> {
      try {
        FutureTask<ScriptXO> task = new FutureTask<>(() -> underTest.read(scriptName));
        Thread.startVirtualThread(task);
        return task.get();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    CompletableFuture<ScriptResultXO> runFuture = CompletableFuture.supplyAsync(() -> {
      try {
        FutureTask<ScriptResultXO> task = new FutureTask<>(() -> underTest.run(scriptName, "args"));
        Thread.startVirtualThread(task);
        return task.get();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });


    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(browseFuture, readFuture, runFuture);
    allFutures.get();

    // Verify results
    List<ScriptXO> browseResult = browseFuture.get();
    assertThat(browseResult, notNullValue());
    assertThat(browseResult.size(), is(1));
    assertThat(browseResult.get(0).getName(), is(scriptName));

    ScriptXO readResult = readFuture.get();
    assertThat(readResult, notNullValue());
    assertThat(readResult.getName(), is(scriptName));

    ScriptResultXO runResult = runFuture.get();
    assertThat(runResult, notNullValue());
    assertThat(runResult.getName(), is(scriptName));
    assertThat(runResult.getResult(), is("Concurrent result"));
  }
}