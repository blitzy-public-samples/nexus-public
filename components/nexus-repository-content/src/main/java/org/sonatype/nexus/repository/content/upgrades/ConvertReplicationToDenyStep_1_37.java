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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.common.db.DatabaseCheck;
import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.common.log.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Convert REPLICATION_ONLY write policy to DENY.
 * <p>
 * This implementation uses Java 21 Virtual Threads for improved I/O performance
 * and String Templates for SQL queries.
 */
@Named
public class ConvertReplicationToDenyStep_1_37
    implements DatabaseMigrationStep
{
  private final DatabaseCheck databaseCheck;
  private final ObjectMapper mapper;
  private final Logger log;

  private static final int PAGE_SIZE = 1000;
  private static final String UPDATE_ATTRIBUTES_BY_ID = "UPDATE repository SET attributes = ? WHERE id = ?";

  @Inject
  public ConvertReplicationToDenyStep_1_37(
      final DatabaseCheck databaseCheck,
      final LogManager logManager) 
  {
    this.mapper = new ObjectMapper();
    this.databaseCheck = databaseCheck;
    this.log = logManager.getLogger(this.getClass());
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.37");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    int totalRows = getTotalRowCount(connection);
    int totalPages = (int) Math.ceil((double) totalRows / PAGE_SIZE);
    
    log.info(STR."Starting migration to convert REPLICATION_ONLY write policy to DENY. Total rows: \{totalRows}, Pages: \{totalPages}");
    
    if (totalRows == 0) {
      log.info("No repository records found to process");
      return;
    }

    // Process pages in parallel using Virtual Threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(totalPages);
      AtomicInteger processedCount = new AtomicInteger(0);
      Map<Integer, Exception> errors = new ConcurrentHashMap<>();

      for (int i = 0; i < totalPages; i++) {
        final int pageIndex = i;
        executor.submit(() -> {
          try {
            int processed = processPage(connection, pageIndex, totalPages);
            processedCount.addAndGet(processed);
            log.debug(STR."Completed processing page \{pageIndex + 1}/\{totalPages} with \{processed} updates");
          } 
          catch (Exception e) {
            log.error(STR."Error processing page \{pageIndex + 1}", e);
            errors.put(pageIndex, e);
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      latch.await();
      
      // Check if any errors occurred
      if (!errors.isEmpty()) {
        throw new RuntimeException(STR."Migration failed with \{errors.size()} errors. First error: \{errors.values().iterator().next().getMessage()}");
      }
      
      log.info(STR."Migration completed successfully. Processed \{processedCount.get()} repositories.");
    }
  }

  /**
   * Process a single page of repository records.
   *
   * @param connection the database connection
   * @param pageIndex the page index (0-based)
   * @param totalPages total number of pages
   * @return number of records processed in this page
   */
  private int processPage(Connection connection, int pageIndex, int totalPages) throws SQLException, JsonProcessingException {
    int offset = pageIndex * PAGE_SIZE;
    int processed = 0;
    
    // Use String Template for SQL query
    String selectQuery = STR."SELECT id, attributes FROM repository LIMIT \{PAGE_SIZE} OFFSET \{offset}";
    
    try (PreparedStatement ps = connection.prepareStatement(selectQuery)) {
      try (ResultSet rs = ps.executeQuery()) {
        List<RepositoryUpdate> updates = new ArrayList<>();
        
        // First pass: identify records that need updates
        while (rs.next()) {
          String id = rs.getString("id");
          String attributes = rs.getString("attributes");

          ObjectNode attributesNode = (ObjectNode) mapper.readTree(attributes);
          JsonNode storageAttributes = attributesNode.get("storage");

          if (storageAttributes != null) {
            JsonNode writePolicyNode = storageAttributes.get("writePolicy");
            if (writePolicyNode != null && "REPLICATION_ONLY".equals(writePolicyNode.asText())) {
              ((ObjectNode) storageAttributes).put("writePolicy", "DENY");
              attributesNode.set("storage", storageAttributes);
              updates.add(new RepositoryUpdate(id, mapper.writeValueAsBytes(attributesNode)));
              processed++;
            }
          }
        }
        
        // Second pass: apply updates in batch
        if (!updates.isEmpty()) {
          try (PreparedStatement updatePs = connection.prepareStatement(UPDATE_ATTRIBUTES_BY_ID)) {
            for (RepositoryUpdate update : updates) {
              if (!databaseCheck.isPostgresql()) {
                updatePs.setBytes(1, update.attributes());
              } else {
                updatePs.setString(1, new String(update.attributes(), UTF_8));
              }
              updatePs.setString(2, update.id());
              updatePs.addBatch();
            }
            updatePs.executeBatch();
          }
        }
      }
    }
    
    return processed;
  }

  /**
   * Get the total number of repository records.
   */
  private int getTotalRowCount(Connection connection) throws SQLException {
    String countQuery = "SELECT COUNT(id) FROM repository";
    try (PreparedStatement ps = connection.prepareStatement(countQuery);
         ResultSet rs = ps.executeQuery()) {
      if (rs.next()) {
        return rs.getInt(1);
      } else {
        throw new SQLException("Failed to count rows in repository table");
      }
    }
  }
  
  /**
   * Record class to hold repository update information.
   */
  private record RepositoryUpdate(String id, byte[] attributes) {}
}