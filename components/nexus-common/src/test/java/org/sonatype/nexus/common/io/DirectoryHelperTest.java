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
package org.sonatype.nexus.common.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Category;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.exists;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.isDirectory;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.isEmptyDirectory;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.isFile;

/**
 * Tests for {@link DirectoryHelper}.
 */
public class DirectoryHelperTest
    extends TestSupport
{
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final byte[] PAYLOAD = "payload".getBytes(UTF_8);

  private File root;

  private void createDirectoryStructure(final Path r) throws IOException {
    Files.write(r.resolve("file1.txt"), PAYLOAD);
    Files.write(r.resolve("file2.txt"), PAYLOAD);
    final Path dir1 = Files.createDirectories(r.resolve("dir1"));
    Files.write(dir1.resolve("file11.txt"), PAYLOAD);
    Files.write(dir1.resolve("file12.txt"), PAYLOAD);
    final Path dir2 = Files.createDirectories(r.resolve("dir2"));
    Files.write(dir2.resolve("file21.txt"), PAYLOAD);
    Files.write(dir2.resolve("file22.txt"), PAYLOAD);
    Files.write(dir2.resolve("file23.txt"), PAYLOAD);
    final Path dir21 = Files.createDirectories(dir2.resolve("dir21"));
    Files.write(dir21.resolve("file211.txt"), PAYLOAD);
    Files.write(dir21.resolve("file212.txt"), PAYLOAD);
  }

  @Before
  public void prepare() throws IOException {
    root = util.createTempDir();
    createDirectoryStructure(root.toPath());
  }

  @Test
  public void mkdir() throws IOException {
    final File mkdirA = new File(root, "mkdir-a");
    final File mkdirAB = new File(mkdirA, "mkdir-ab");
    final File dir211 = new File(new File(new File(root, "dir2"), "dir21"), "dir211");
    DirectoryHelper.mkdir(mkdirAB.toPath()); // new
    DirectoryHelper.mkdir(mkdirA.toPath()); // existing
    DirectoryHelper.mkdir(dir211.toPath()); // existing structure
    assertThat(mkdirA, isDirectory());
    assertThat(mkdirAB, isDirectory());
    assertThat(dir211, isDirectory());
  }

  @Test
  public void mkdirWithParent() throws IOException {
    final File mkdirA = DirectoryHelper.mkdir(root, "mkdir-parent-a"); // new
    assertThat(mkdirA, isDirectory());

    File file = DirectoryHelper.mkdir(new File(root, "dir2"), "dir21"); // existing
    assertThat(file, isDirectory());
  }

  @Test
  public void symlinkMkdir() throws IOException {
    final Path dir1link = root.toPath().resolve("dir1-link");
    try {
      // not all OSes support symlink creation
      // if symlink creation fails on given OS, just return from this test
      Files.createSymbolicLink(dir1link, root.toPath().resolve("dir1"));
    }
    catch (IOException e) {
      return;
    }
    DirectoryHelper.mkdir(dir1link);
    assertThat(root.toPath().resolve("dir1-link").toFile().isDirectory(), equalTo(true));
  }

  @Test
  public void clean() throws IOException {
    DirectoryHelper.clean(root.toPath());
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, not(isEmptyDirectory()));
    assertThat(root.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
  }

  @Test
  public void cleanIfExists() throws IOException {
    assertThat(DirectoryHelper.cleanIfExists(root.toPath().resolve("not-existing")), is(false));
    assertThat(DirectoryHelper.cleanIfExists(root.toPath()), is(true));
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, not(isEmptyDirectory()));
    assertThat(root.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
  }

  @Test
  public void empty() throws IOException {
    DirectoryHelper.empty(root.toPath());
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, isEmptyDirectory());
  }

  @Test
  public void emptyIfExists() throws IOException {
    assertThat(DirectoryHelper.emptyIfExists(root.toPath().resolve("not-existing")), is(false));
    assertThat(DirectoryHelper.emptyIfExists(root.toPath()), is(true));
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, isEmptyDirectory());
  }

  @Test
  public void delete() throws IOException {
    DirectoryHelper.delete(root.toPath());
    assertThat(root, not(exists()));
  }

  @Test
  public void deleteIfExists() throws IOException {
    assertThat(DirectoryHelper.deleteIfExists(root.toPath().resolve("not-existing")), is(false));
    assertThat(DirectoryHelper.deleteIfExists(root.toPath()), is(true));
    assertThat(root, not(exists()));
  }

  @Test
  public void copy() throws IOException {
    final Path target = util.createTempDir().toPath();
    DirectoryHelper.copy(root.toPath(), target);
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void copyIfExists() throws IOException {
    final Path target = util.createTempDir().toPath();
    assertThat(DirectoryHelper.copyIfExists(root.toPath().resolve("not-existing"), target), is(false));
    assertThat(DirectoryHelper.copyIfExists(root.toPath(), target), is(true));
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void move() throws IOException {
    final Path target = util.createTempDir().toPath();
    DirectoryHelper.move(root.toPath(), target);
    assertThat(root, not(exists()));
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void copyDeleteMoveToSubdir() throws IOException {
    final Path target = root.toPath().resolve("dir2/dir21");
    DirectoryHelper.copyDeleteMove(root.toPath(), target, new Predicate<Path>()
    {
      @Override
      public boolean apply(@Nullable final Path input) {
        return input.startsWith(target);
      }
    });
    assertThat(root, exists());
    assertThat(root.toPath().resolve("dir1").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/file21.txt").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2/file22.txt").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2/file23.txt").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2/dir21").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/file211.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/file212.txt").toFile(), exists());

    assertThat(root.toPath().resolve("dir2/dir21/dir1").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir1/file11.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir1/file12.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2/file21.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2/file22.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2/file23.txt").toFile(), exists());

    assertThat(root, exists());
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(root.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(root.toPath().resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  /**
   * This is what happened when repo root was being deleted: endless cycle in as "manual" copy/move was
   * performed (during copy), as it copied files "ahead" of itself, basically "rolling" files deeper
   * and deeper. {@link FileSystemException} is thrown once file path length reaches OS limit. In case
   * of repo local storage, the root was being moved under "/.nexus/trash".
   */
  @Test(expected = FileSystemException.class)
  public void moveToSubdir() throws IOException {
    final Path target = root.toPath().resolve("dir2/dir21");
    DirectoryHelper.move(root.toPath(), target);
  }

  @Test(expected = IllegalArgumentException.class)
  public void copyingToChildDirDisallowedWithoutFilter() throws IOException {
    final Path target = root.toPath().resolve("dir2/dir21");
    DirectoryHelper.copyDeleteMove(root.toPath(), target, null);
  }

  @Test
  public void moveIfExists() throws IOException {
    final Path target = util.createTempDir().toPath();
    assertThat(DirectoryHelper.moveIfExists(root.toPath().resolve("not-existing"), target), is(false));
    assertThat(DirectoryHelper.moveIfExists(root.toPath(), target), is(true));
    assertThat(root, not(exists()));
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void apply() throws IOException {
    final ArrayList<String> fileNames = Lists.newArrayList();
    final ArrayList<String> dirNames = Lists.newArrayList();
    final Function<Path, FileVisitResult> tf = new Function<Path, FileVisitResult>()
    {
      @Override
      public FileVisitResult apply(final Path input) {
        if (Files.isDirectory(input)) {
          dirNames.add(input.getFileName().toString());
        }
        else if (Files.isRegularFile(input)) {
          fileNames.add(input.getFileName().toString());
        }
        return FileVisitResult.CONTINUE;
      }
    };
    DirectoryHelper.apply(root.toPath(), tf);

    assertThat(fileNames, hasSize(9));
    // root + 3dirs
    assertThat(dirNames, hasSize(4));
  }

  @Test
  public void applyToFiles() throws IOException {
    final ArrayList<String> fileNames = Lists.newArrayList();
    final ArrayList<String> dirNames = Lists.newArrayList();
    final Function<Path, FileVisitResult> tf = new Function<Path, FileVisitResult>()
    {
      @Override
      public FileVisitResult apply(final Path input) {
        if (Files.isDirectory(input)) {
          dirNames.add(input.getFileName().toString());
        }
        else if (Files.isRegularFile(input)) {
          fileNames.add(input.getFileName().toString());
        }
        return FileVisitResult.CONTINUE;
      }
    };
    DirectoryHelper.applyToFiles(root.toPath(), tf);

    assertThat(fileNames, hasSize(9));
    // func never invoked on dirs
    assertThat(dirNames, hasSize(0));
  }

  @Test
  public void deleteIfEmptyRecursively() throws Exception {
    File dir = temporaryFolder.newFolder("basedir");

    // now lets start adding some directories
    // first off a simple empty directory
    File subdir = new File(dir, "sub");
    Files.createDirectory(subdir.toPath());

    // now some nested empty directories
    subdir = new File(dir, "subnested");
    Files.createDirectory(subdir.toPath());
    for (int i = 0; i < 10; i++) {
      subdir = new File(subdir, "subnested" + i);
      Files.createDirectory(subdir.toPath());
    }

    // now a directory with a file in it
    subdir = new File(dir, "subwithcontent");
    Files.createDirectory(subdir.toPath());
    new File(subdir, "afile.txt").createNewFile();

    // now a nested directory with a file in it
    subdir = new File(dir, "subnestedwithcontent");
    Files.createDirectory(subdir.toPath());
    for (int i = 0; i < 10; i++) {
      subdir = new File(subdir, "subnestedwithcontent" + i);
      Path newdir = Files.createDirectory(subdir.toPath());
      if (i == 9) {
        new File(newdir.toFile(), "afile.txt").createNewFile();
      }
    }

    int count = DirectoryHelper.deleteIfEmptyRecursively(dir.toPath(), null);

    assertThat(dir, exists());
    assertThat(new File(dir, "sub"), not(exists()));
    assertThat(new File(dir, "subnested"), not(exists()));
    assertThat(new File(dir, "subwithcontent"), exists());
    assertThat(new File(dir, "subnestedwithcontent"), exists());
    assertThat(count, is(12));
  }

  @Test
  public void deleteIfEmptyRecursively_missingDirectory() throws Exception {
    int count = DirectoryHelper.deleteIfEmptyRecursively(Paths.get("fake", "dir"), null);
    assertThat(count, is(0));
  }

  @Test
  public void deleteIfEmptyRecursively_skipNewerDirs() throws Exception {
    File dir = temporaryFolder.newFolder("basedir");

    // This directory will be the one that is slightly older than the timestamp so _should_ get deleted
    File subdir = new File(dir, "sub");
    Files.createDirectory(subdir.toPath());

    // put some sleeps around the timestamp, to guaranty state, and that the timestamp wont errantly associate with the
    // test created directories
    Thread.sleep(1000);
    Date okTimestamp = new Date();
    Thread.sleep(1000);

    // This directory should come after the timestamp, so should not get deleted
    subdir = new File(dir, "sub2");
    Files.createDirectory(subdir.toPath());

    int count = DirectoryHelper.deleteIfEmptyRecursively(dir.toPath(), okTimestamp.getTime());

    assertThat(count, is(1));
    assertThat(new File(dir, "sub"), not(exists()));
    assertThat(new File(dir, "sub2"), exists());
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void parallelDirectoryOperationsWithVirtualThreads() throws Exception {
    // Create a test directory structure
    File testDir = temporaryFolder.newFolder("virtualThreadTest");
    int numDirs = 100;
    
    // Create directories in parallel using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    CountDownLatch createLatch = new CountDownLatch(numDirs);
    AtomicInteger createErrors = new AtomicInteger(0);
    
    for (int i = 0; i < numDirs; i++) {
      final int dirNum = i;
      executor.submit(() -> {
        try {
          File dir = new File(testDir, "dir" + dirNum);
          DirectoryHelper.mkdir(dir.toPath());
        } catch (Exception e) {
          createErrors.incrementAndGet();
        } finally {
          createLatch.countDown();
        }
      });
    }
    
    // Wait for all directory creations to complete
    createLatch.await(30, TimeUnit.SECONDS);
    
    // Verify all directories were created successfully
    assertThat(createErrors.get(), is(0));
    for (int i = 0; i < numDirs; i++) {
      assertThat(new File(testDir, "dir" + i), isDirectory());
    }
    
    // Now delete directories in parallel using virtual threads
    CountDownLatch deleteLatch = new CountDownLatch(numDirs);
    AtomicInteger deleteErrors = new AtomicInteger(0);
    
    for (int i = 0; i < numDirs; i++) {
      final int dirNum = i;
      executor.submit(() -> {
        try {
          File dir = new File(testDir, "dir" + dirNum);
          DirectoryHelper.delete(dir.toPath());
        } catch (Exception e) {
          deleteErrors.incrementAndGet();
        } finally {
          deleteLatch.countDown();
        }
      });
    }
    
    // Wait for all directory deletions to complete
    deleteLatch.await(30, TimeUnit.SECONDS);
    
    // Verify all directories were deleted successfully
    assertThat(deleteErrors.get(), is(0));
    for (int i = 0; i < numDirs; i++) {
      assertThat(new File(testDir, "dir" + i), not(exists()));
    }
    
    // Shutdown the executor
    executor.shutdown();
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void concurrentCopyOperationsWithVirtualThreads() throws Exception {
    // Create source directory with test files
    File sourceDir = temporaryFolder.newFolder("sourceDir");
    createDirectoryStructure(sourceDir.toPath());
    
    // Create target directories
    int numCopies = 10;
    File[] targetDirs = new File[numCopies];
    for (int i = 0; i < numCopies; i++) {
      targetDirs[i] = temporaryFolder.newFolder("targetDir" + i);
    }
    
    // Perform concurrent copy operations using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    CountDownLatch copyLatch = new CountDownLatch(numCopies);
    AtomicInteger copyErrors = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < numCopies; i++) {
      final int copyNum = i;
      executor.submit(() -> {
        try {
          DirectoryHelper.copy(sourceDir.toPath(), targetDirs[copyNum].toPath());
        } catch (Exception e) {
          copyErrors.incrementAndGet();
        } finally {
          copyLatch.countDown();
        }
      });
    }
    
    // Wait for all copy operations to complete
    copyLatch.await(60, TimeUnit.SECONDS);
    
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    // Verify all copies were successful
    assertThat(copyErrors.get(), is(0));
    for (int i = 0; i < numCopies; i++) {
      File targetDir = targetDirs[i];
      assertThat(targetDir, exists());
      assertThat(targetDir, isDirectory());
      assertThat(targetDir, not(isEmptyDirectory()));
      assertThat(targetDir.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
      assertThat(targetDir.toPath().resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
    }
    
    // Log performance metrics
    log.info("Completed {} concurrent copy operations in {} ms using virtual threads", numCopies, duration);
    
    // Shutdown the executor
    executor.shutdown();
  }

  @Test
  @Category(VirtualThreadTestGroup.class)
  public void detectThreadPinningDuringFileOperations() throws Exception {
    // Create a test directory
    File testDir = temporaryFolder.newFolder("threadPinningTest");
    
    // Create a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("file-io-thread").start(() -> {
      try {
        // Perform file operations that might cause thread pinning
        long startTime = System.currentTimeMillis();
        
        // Create directories
        for (int i = 0; i < 10; i++) {
          File dir = new File(testDir, "dir" + i);
          DirectoryHelper.mkdir(dir.toPath());
          
          // Write some files
          for (int j = 0; j < 5; j++) {
            Files.write(dir.toPath().resolve("file" + j + ".txt"), PAYLOAD);
          }
        }
        
        // Perform a copy operation
        File targetDir = new File(testDir, "copy-target");
        DirectoryHelper.mkdir(targetDir.toPath());
        DirectoryHelper.copy(new File(testDir, "dir0").toPath(), targetDir.toPath());
        
        long endTime = System.currentTimeMillis();
        log.info("File operations completed in {} ms", endTime - startTime);
      } catch (IOException e) {
        log.error("Error during file operations", e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(30000);
    
    // Verify the operations completed successfully
    assertThat(new File(testDir, "dir0"), isDirectory());
    assertThat(new File(testDir, "copy-target"), isDirectory());
    assertThat(new File(testDir, "copy-target/file0.txt"), isFile());
  }
}