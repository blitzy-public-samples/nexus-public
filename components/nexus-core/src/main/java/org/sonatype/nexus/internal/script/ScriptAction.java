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
package org.sonatype.nexus.internal.script;

import javax.inject.Inject;
import javax.inject.Named;
import javax.script.ScriptContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.commands.SessionAware;
import org.sonatype.nexus.common.script.ScriptService;

import org.apache.karaf.shell.api.action.Action;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.console.Session;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkState;

/**
 * Action to execute a script.
 *
 * @since 3.0
 */
@Named
@Command(name = "script", scope = "nexus", description = "Execute a script")
public class ScriptAction
    extends ComponentSupport
    implements Action, SessionAware
{

  @Inject
  private ScriptService scripts;

  @Option(name = "-l", aliases = "--language", required = false, description = "Script language",
      valueToShowInHelp = ScriptEngineManagerProvider.DEFAULT_LANGUAGE)
  private String language;

  @Option(name = "-f", aliases = "--file", required = false, description = "Script file")
  private File file;

  @Option(name = "-u", aliases = "--url", required = false, description = "Script URL")
  private URL url;

  @Option(name = "-e", aliases = "--expression", required = false, description = "Script expression")
  private String expression;

  @Argument(name = "args", index = 0, multiValued = true, required = false, description = "Script arguments")
  private List<String> args;

  private Session session;

  @Override
  public void setSession(Session session) {
    this.session = session;
  }

  @Override
  public Object execute() throws Exception {
    // ensure only one source was configured
    int sources = 0;
    if (file != null)
      sources++;
    if (url != null)
      sources++;
    if (expression != null)
      sources++;
    checkState(sources == 1, "One (and only one) of --file, --url or --expression must be specified");

    // resolve source text using pattern matching for switch
    String source = switch (Object.class) {
      case null -> throw new IllegalStateException("No source available");
      
      // File source
      case Object o when file != null -> {
        log.debug(STR."Source file: \{file}");
        yield Files.readString(file.toPath(), StandardCharsets.UTF_8);
      }
      
      // URL source
      case Object o when url != null -> {
        log.debug(STR."Source URL: \{url}");
        try (ReadableByteChannel channel = java.nio.channels.Channels.newChannel(url.openStream());
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
          
          // Use NIO for more efficient transfer
          java.nio.channels.Channels.newChannel(outputStream).transferFrom(channel, 0, Integer.MAX_VALUE);
          yield outputStream.toString(StandardCharsets.UTF_8);
        }
      }
      
      // Expression source
      case Object o when expression != null -> {
        log.debug(STR."Source expression: \{expression}");
        yield expression;
      }
      
      default -> throw new IllegalStateException("No source available");
    };

    language = language != null ? language : ScriptEngineManagerProvider.DEFAULT_LANGUAGE;

    // construct new context for execution
    ScriptContext context = scripts.createContext(language);

    // Use virtual thread for script execution to improve I/O performance
    CompletableFuture<Object> future = new CompletableFuture<>();
    
    Thread.startVirtualThread(() -> {
      try {
        // adapt to session streams
        try (InputStreamReader keyboardReader = new InputStreamReader(session.getKeyboard());
             OutputStreamWriter consoleWriter = new OutputStreamWriter(session.getConsole());
             OutputStreamWriter errorWriter = new OutputStreamWriter(session.getConsole())) {
          context.setReader(keyboardReader);
          context.setWriter(consoleWriter);
          context.setErrorWriter(errorWriter);
          
          // customize scope for execution
          scripts.customizeBindings(context, java.util.Map.of(
              "log", LoggerFactory.getLogger(ScriptAction.class),
              "session", session,
              "args", args));

          // execute script
          log.debug(STR."Evaluating script: \{source}");
          Object result = scripts.eval(language, source, context);
          log.debug(STR."Result: \{result}");

          future.complete(result);
        }
      } catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    try {
      return future.join();
    } catch (Exception e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }
}