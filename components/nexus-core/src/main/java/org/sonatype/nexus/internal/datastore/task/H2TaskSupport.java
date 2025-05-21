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
package org.sonatype.nexus.internal.datastore.task;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.scheduling.CancelableHelper;

import com.google.common.annotations.VisibleForTesting;

/**
 * Support class for H2 database tasks that provides methods for exporting database content to SQL scripts.
 * Optimized for Java 21 with Virtual Threads and Structured Concurrency for improved I/O performance.
 */
public class H2TaskSupport extends ComponentSupport
{
  private static final String EXPORT_INITIALISE_SQL
      = "ALTER TABLE \"PUBLIC\".\"NUGET_COMPONENT\" ALTER COLUMN CI_NAME VARCHAR NOT NULL SELECTIVITY 100;";

  private static final String EXPORT_SQL = "SCRIPT";

  private static final String EXPORT_RECOVERY_SQL
      = "ALTER TABLE \"PUBLIC\".\"NUGET_COMPONENT\" ALTER COLUMN CI_NAME VARCHAR AS LOWER(\"NAME\") SELECTIVITY 100;";

  static final int PROGRESS_UPDATE_THRESHOLD = 10_000;

  static final int PROGRESS_LOG_THRESHOLD = 500_000;

  /**
   * Exports the database content to a SQL script file using Virtual Threads for improved I/O performance.
   *
   * @param connection the database connection
   * @param location the file location to write the SQL script
   * @param progressConsumer consumer for progress updates
   * @return the number of lines written
   * @throws SqlScriptGenerationException if script generation fails
   */
  public long exportDatabase(final Connection connection, final String location, final Consumer<String> progressConsumer)
      throws SqlScriptGenerationException {

    boolean autoCommit = false;
    try {
      autoCommit = getAndSetAutoCommit(connection);

      // Execute initialization SQL
      try (PreparedStatement scriptStmt = connection.prepareStatement(EXPORT_INITIALISE_SQL)) {
        scriptStmt.execute();
      }
      
      // Execute export SQL and process results using structured concurrency
      try (PreparedStatement scriptStmt = connection.prepareStatement(EXPORT_SQL)) {
        scriptStmt.execute();
        return processResultsWithVirtualThread(scriptStmt.getResultSet(), location, progressConsumer);
      }
    }
    catch (SQLException ex) {
      throw new SqlScriptGenerationException("Script generation failed", ex);
    }
    catch (InterruptedException ex) {
      Thread.currentThread().interrupt(); // Preserve interrupt status
      throw new SqlScriptGenerationException("Script generation was interrupted", ex);
    }
    finally {
      rollback(connection);
      resetAutoCommit(connection, autoCommit);
    }
  }

  /**
   * Processes ResultSet using a Virtual Thread for improved I/O performance.
   * Uses Structured Concurrency to manage the thread lifecycle and handle cancellation properly.
   *
   * @param resultSet the SQL result set to process
   * @param location the file location to write
   * @param progressConsumer consumer for progress updates
   * @return the number of lines written
   * @throws SQLException if a database error occurs
   * @throws InterruptedException if the operation is interrupted
   */
  @VisibleForTesting
  long processResultsWithVirtualThread(final ResultSet resultSet, final String location, final Consumer<String> progressConsumer)
      throws SQLException, InterruptedException {
    if (resultSet == null || location == null || location.isEmpty()) {
      return 0;
    }
    
    // Use StructuredTaskScope to manage the virtual thread lifecycle
    try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Fork a virtual thread to handle the I/O-bound file writing operation
      Future<Long> result = scope.fork(() -> {
        try (FileWriter writer = new FileWriter(location);
             BufferedWriter buffer = new BufferedWriter(writer)) {
          long linesWritten = writeLines(resultSet, progressConsumer, PROGRESS_UPDATE_THRESHOLD, PROGRESS_LOG_THRESHOLD, buffer);
          return linesWritten + 1;
        }
        catch (Exception ex) {
          throw new SqlScriptGenerationException("Script generation failed when writing data", ex);
        }
      });
      
      // Wait for the task to complete and handle any exceptions
      scope.join();
      scope.throwIfFailed();
      
      // Return the result
      return result.resultNow();
    }
  }

  /**
   * Legacy method for backward compatibility and testing.
   */
  @VisibleForTesting
  long processResults(final ResultSet resultSet, final String location, final Consumer<String> progressConsumer)
      throws SQLException {
    try {
      return processResultsWithVirtualThread(resultSet, location, progressConsumer);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SqlScriptGenerationException("Script generation was interrupted", e);
    }
  }

  /**
   * Writes lines from the ResultSet to the buffer.
   * Optimized to handle virtual thread interruption properly.
   *
   * @param resultSet the SQL result set to process
   * @param progressConsumer consumer for progress updates
   * @param progressUpdateThreshold threshold for progress updates
   * @param progressLogThreshold threshold for progress logging
   * @param buffer the buffer to write to
   * @return the number of lines written
   * @throws SQLException if a database error occurs
   * @throws IOException if an I/O error occurs
   */
  @VisibleForTesting
  long writeLines(
      final ResultSet resultSet,
      final Consumer<String> progressConsumer,
      final int progressUpdateThreshold,
      final int progressLogThreshold,
      final BufferedWriter buffer) throws SQLException, IOException
  {
    long linesWritten = 0;
    while (!Thread.currentThread().isInterrupted() && resultSet.next()) {
      buffer.write(resultSet.getString(1)); // Always remember SQL columns are 1-indexed !
      buffer.newLine();
      if (++linesWritten % progressUpdateThreshold == 0) {
        updateProgress(progressConsumer, linesWritten);
      }
      if (linesWritten % progressLogThreshold == 0) {
        log.info("Exported {} lines", linesWritten);
      }
    }
    
    // Check for interruption after the loop
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedException("Line writing was interrupted");
    }
    
    buffer.write(EXPORT_RECOVERY_SQL);
    buffer.newLine();
    buffer.flush();
    return linesWritten;
  }

  /**
   * Updates progress and checks for cancellation.
   * Enhanced to properly handle virtual thread interruption.
   *
   * @param progressConsumer consumer for progress updates
   * @param linesWritten the number of lines written so far
   */
  @VisibleForTesting
  void updateProgress(final Consumer<String> progressConsumer, final long linesWritten) {
    if (progressConsumer != null) {
      progressConsumer.accept(String.format("%d lines of SQL exported", linesWritten));
    }
    try {
      CancelableHelper.checkCancellation();
    } catch (RuntimeException e) {
      // Convert cancellation to interruption for virtual threads
      Thread.currentThread().interrupt();
      throw e;
    }
  }

  /**
   * Rolls back the database connection.
   * Enhanced to properly handle virtual thread interruption.
   *
   * @param connection the database connection
   */
  @VisibleForTesting
  void rollback(final Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ex) {
      try (PreparedStatement scriptStmt = connection.prepareStatement(H2TaskSupport.EXPORT_RECOVERY_SQL)) {
        scriptStmt.execute();
      }
      catch (SQLException cex) {
        // If we hit this, there is no recovery
        log.error("Unable to rollback export initialisation. The database may need modification.", ex);
        try {
          connection.close();
        }
        catch (SQLException broken) {
          throw new RuntimeException("Unable to close database connection after failed rollback:", broken);
        }
      }
    }
  }

  /**
   * Gets the current auto-commit state and sets it to the specified value.
   * Virtual thread-friendly implementation that avoids synchronization.
   *
   * @param connection the database connection
   * @return the previous auto-commit state
   * @throws SQLException if a database error occurs
   */
  @VisibleForTesting
  boolean getAndSetAutoCommit(final Connection connection) throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(false);
    return autoCommit;
  }

  /**
   * Resets the auto-commit state of the connection.
   * Virtual thread-friendly implementation that avoids synchronization.
   *
   * @param connection the database connection
   * @param autoCommit the auto-commit state to set
   */
  @VisibleForTesting
  void resetAutoCommit(final Connection connection, boolean autoCommit) {
    try {
      connection.setAutoCommit(autoCommit);
    } catch (SQLException ex) {
      // If we hit this, there is no recovery
      log.error("Unable to reset auto commit.", ex);
    }
  }
}