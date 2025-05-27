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
package org.sonatype.nexus.coreui.internal.wonderland;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.core.Response;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.wonderland.AuthTicketService;
import org.sonatype.nexus.common.wonderland.DownloadService;
import org.sonatype.nexus.common.wonderland.DownloadService.Download;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DownloadResourceTest
    extends TestSupport
{
  @Mock
  private DownloadService downloadService;

  @Mock
  private AuthTicketService authTicketService;

  /**
   * Fix for NEXUS-40992
   */
  @Test
  public void downloadZipShouldUseCorrectFileNameHeader() throws IOException {
    DownloadResource underTest = new DownloadResource(downloadService, authTicketService);
    String fileName = "supportZip-timestamp.zip";
    mockAuthenticatedDownload(fileName);

    Response response = underTest.downloadZip(fileName);

    assertEquals("attachment; filename=\"" + fileName + "\"", response.getHeaderString(CONTENT_DISPOSITION));
  }

  /**
   * Test that Virtual Threads can handle multiple concurrent download requests efficiently
   */
  @Test
  public void downloadZipWithVirtualThreadsShouldHandleMultipleConcurrentRequests() throws Exception {
    DownloadResource underTest = new DownloadResource(downloadService, authTicketService);
    int numThreads = 10;
    CountDownLatch latch = new CountDownLatch(numThreads);
    AtomicInteger successCount = new AtomicInteger(0);
    List<Thread> threads = new ArrayList<>();
    
    // Create multiple virtual threads to simulate concurrent requests
    for (int i = 0; i < numThreads; i++) {
      String fileName = "supportZip-" + i + ".zip";
      Thread thread = Thread.startVirtualThread(() -> {
        try {
          // Mock the download for this specific thread
          String fileAuthTicket = fileName + "-authTicket";
          when(authTicketService.createTicket()).thenReturn(fileAuthTicket);
          
          // Create a mock Download with a simple byte stream
          byte[] content = ("content for " + fileName).getBytes();
          InputStream inputStream = new ByteArrayInputStream(content);
          Download mockDownload = new Download(content.length, inputStream);
          when(downloadService.get(fileName, fileAuthTicket)).thenReturn(mockDownload);
          
          // Execute the download
          Response response = underTest.downloadZip(fileName);
          
          // Verify the response
          assertNotNull(response);
          assertEquals("attachment; filename=\"" + fileName + "\"", response.getHeaderString(CONTENT_DISPOSITION));
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          log.error("Error in virtual thread", e);
        }
        finally {
          latch.countDown();
        }
      });
      threads.add(thread);
    }
    
    // Wait for all threads to complete
    latch.await();
    
    // Verify all downloads were successful
    assertEquals(numThreads, successCount.get(), "All virtual thread downloads should succeed");
  }

  private void mockAuthenticatedDownload(String fileName) {
    Download mockDownload = mock(Download.class);
    String fileAuthTicket = fileName + "-authTicket";
    when(authTicketService.createTicket()).thenReturn(fileAuthTicket);
    try {
      when(downloadService.get(fileName, fileAuthTicket)).thenReturn(mockDownload);
    } catch (IOException e) {
      // swallow exception that will never happen
    }
  }
}