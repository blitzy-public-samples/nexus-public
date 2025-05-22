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
package org.sonatype.nexus.siesta;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.rest.ExceptionMapperSupport;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests error handling.
 */
public class ErrorsIT
    extends SiestaTestSupport
{
  @Test
  public void errorResponseHasFaultId() {
    WebTarget target = client().target(url("errors/406"));
    Response response = target.request().get(Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getStatusCode(), equalTo(406));
    String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
    log("Fault ID: {}", faultId);
    assertThat(faultId, notNullValue());
  }
  
  @Test
  public void badRequestErrorResponseHasFaultId() {
    WebTarget target = client().target(url("errors/BadRequestException"));
    Response response = target.request().get(Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getStatusCode(), equalTo(400));
    String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
    log("Fault ID: {}", faultId);
    assertThat(faultId, notNullValue());
  }
  
  @Test
  public void notFoundErrorResponseHasFaultId() {
    WebTarget target = client().target(url("errors/NotFoundException"));
    Response response = target.request().get(Response.class);
    log("Status: {}", response.getStatusInfo());

    assertThat(response.getStatusInfo().getStatusCode(), equalTo(404));
    String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
    log("Fault ID: {}", faultId);
    assertThat(faultId, notNullValue());
  }
  
  @Test
  public void concurrentErrorRequestsWithVirtualThreads() {
    // Number of concurrent requests to make
    final int concurrentRequests = 50;
    
    // Create a list to hold all the futures
    List<CompletableFuture<Response>> futures = new ArrayList<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create the WebTarget once and reuse it
      WebTarget target = client().target(url("errors/406"));
      
      // Submit concurrent requests
      for (int i = 0; i < concurrentRequests; i++) {
        CompletableFuture<Response> future = CompletableFuture.supplyAsync(() -> {
          Response response = target.request().get(Response.class);
          log("Thread: {}, Status: {}", Thread.currentThread().getName(), response.getStatusInfo());
          return response;
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all futures to complete and verify the responses
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      // Verify each response
      for (CompletableFuture<Response> future : futures) {
        Response response = future.join();
        assertThat(response.getStatusInfo().getStatusCode(), equalTo(406));
        String faultId = response.getHeaderString(ExceptionMapperSupport.X_SIESTA_FAULT_ID);
        assertThat(faultId, notNullValue());
        
        // Ensure proper cleanup
        response.close();
      }
    }
  }
}