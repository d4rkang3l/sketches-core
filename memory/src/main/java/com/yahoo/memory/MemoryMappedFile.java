/*
 * Copyright 2015, Yahoo! Inc. Licensed under the terms of the Apache License 2.0. See LICENSE
 * file at the project root for terms.
 */

package com.yahoo.memory;

import static com.yahoo.memory.UnsafeUtil.unsafe;

import java.io.File;
import java.io.FileDescriptor;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

import sun.misc.Cleaner;
import sun.nio.ch.FileChannelImpl;

/**
 * MemoryMappedFile class extends NativeMemory and is used to memory map files (including those &gt;
 * 2GB) off heap.
 *
 * <p>This class leverages the JVM Cleaner class that replaces {@link java.lang.Object#finalize()}
 * and serves as a back-up if the calling class does not call {@link #freeMemory()}.</p>
 *
 * @author Praveenkumar Venkatesan
 */
//@SuppressWarnings("restriction")
public final class MemoryMappedFile extends NativeMemory {

  private RandomAccessFile randomAccessFile_ = null;
  private MappedByteBuffer dummyMbbInstance_ = null;
  private final Cleaner cleaner_;

  private MemoryMappedFile(final RandomAccessFile raf, final MappedByteBuffer mbb,
      final long nativeBaseAddress, final long capacityBytes) {
    super(nativeBaseAddress, capacityBytes, 0L, null, null);
    randomAccessFile_ = raf;
    dummyMbbInstance_ = mbb;
    cleaner_ = Cleaner.create(this,
        new Deallocator(randomAccessFile_, nativeBaseAddress, capacityBytes));
  }

  /**
   * Factory method for creating a memory mapping a file.
   *
   * <p>Memory maps a file directly in off heap leveraging native map0 method used in
   * FileChannelImpl.c. The owner will have read write access to that address space.</p>
   *
   * @param file File to be mapped
   * @param position Memory map starting from this position in the file
   * @param len Memory map at most len bytes &gt; 0 starting from {@code position}
   * @return A new MemoryMappedFile
   * @throws Exception file not found or RuntimeException, etc.
   */
  @SuppressWarnings("resource")
  public static MemoryMappedFile getInstance(final File file, final long position, final long len)
      throws Exception {
    checkPositionLen(position, len);

    final RandomAccessFile raf = new RandomAccessFile(file, "rw");
    final FileChannel fc = raf.getChannel();
    final long nativeBaseAddress = map(fc, position, len);
    final long capacityBytes = len;

    // len can be more than the file.length
    raf.setLength(len);
    final MappedByteBuffer mbb = createDummyMbbInstance(nativeBaseAddress);

    return new MemoryMappedFile(raf, mbb, nativeBaseAddress, capacityBytes);
  }

  private static final void checkPositionLen(final long position, final long len) {
    if (position < 0L) {
      throw new IllegalArgumentException("Negative position");
    }
    if (len < 0L) {
      throw new IllegalArgumentException("Negative size");
    }
    if (position + len < 0) {
      throw new IllegalArgumentException("Position + size overflow");
    }
  }

  /**
   * Loads content into physical memory. This method makes a best effort to ensure that, when it
   * returns, this buffer's content is resident in physical memory. Invoking this method may cause
   * some number of page faults and I/O operations to occur.
   *
   * @see <a href="https://docs.oracle.com/javase/8/docs/api/java/nio/MappedByteBuffer.html#load--">
   * java/nio/MappedByteBuffer.load</a>
   */
  public void load() {
    madvise();

    // Read a byte from each page to bring it into memory.
    final int ps = unsafe.pageSize();
    final int count = pageCount(ps, capacityBytes_);
    long a = nativeBaseAddress_;
    for (int i = 0; i < count; i++) {
      unsafe.getByte(a);
      a += ps;
    }
  }

  /**
   * Tells whether or not the content is resident in physical memory. A return value of true implies
   * that it is highly likely that all of the data in this buffer is resident in physical memory and
   * may therefore be accessed without incurring any virtual-memory page faults or I/O operations. A
   * return value of false does not necessarily imply that the content is not resident in physical
   * memory. The returned value is a hint, rather than a guarantee, because the underlying operating
   * system may have paged out some of the buffer's data by the time that an invocation of this
   * method returns.
   *
   * @return true if loaded
   *
   * @see <a href=
   * "https://docs.oracle.com/javase/8/docs/api/java/nio/MappedByteBuffer.html#isLoaded--"> java
   * /nio/MappedByteBuffer.isLoaded</a>
   */
  public boolean isLoaded() {
    try {
      final int ps = unsafe.pageSize();
      final int pageCount = pageCount(ps, capacityBytes_);
      final Method method =
          MappedByteBuffer.class.getDeclaredMethod("isLoaded0", long.class, long.class, int.class);
      method.setAccessible(true);
      return (boolean) method.invoke(dummyMbbInstance_, nativeBaseAddress_, capacityBytes_,
          pageCount);
    } catch (final Exception e) {
      throw new RuntimeException(
          String.format("Encountered %s exception while loading", e.getClass()));
    }
  }

  /**
   * Forces any changes made to this content to be written to the storage device containing the
   * mapped file.
   *
   * <p>
   * If the file mapped into this buffer resides on a local storage device then when this method
   * returns it is guaranteed that all changes made to the buffer since it was created, or since
   * this method was last invoked, will have been written to that device.
   * </p>
   *
   * <p>
   * If the file does not reside on a local device then no such guarantee is made.
   * </p>
   *
   * <p>
   * If this buffer was not mapped in read/write mode
   * (java.nio.channels.FileChannel.MapMode.READ_WRITE) then invoking this method has no effect.
   * </p>
   *
   * @see <a href=
   * "https://docs.oracle.com/javase/8/docs/api/java/nio/MappedByteBuffer.html#force--"> java/
   * nio/MappedByteBuffer.force</a>
   */
  public void force() {
    try {
      final Method method = MappedByteBuffer.class.getDeclaredMethod("force0", FileDescriptor.class,
          long.class, long.class);
      method.setAccessible(true);
      method.invoke(dummyMbbInstance_, randomAccessFile_.getFD(), nativeBaseAddress_,
          capacityBytes_);
    } catch (final Exception e) {
      throw new RuntimeException(String.format("Encountered %s exception in force", e.getClass()));
    }
  }

  @Override
  public void freeMemory() {
    cleaner_.clean();
    super.freeMemory();
  }

  // Restricted methods

  static final int pageCount(final int ps, final long length) {
    return (int) ( (length == 0) ? 0 : (length - 1L) / ps + 1L);
  }

  private static final MappedByteBuffer createDummyMbbInstance(final long nativeBaseAddress)
      throws RuntimeException {
    try {
      final Class<?> cl = Class.forName("java.nio.DirectByteBuffer");
      final Constructor<?> ctor =
          cl.getDeclaredConstructor(int.class, long.class, FileDescriptor.class, Runnable.class);
      ctor.setAccessible(true);
      final MappedByteBuffer mbb = (MappedByteBuffer) ctor.newInstance(0, // some junk capacity
          nativeBaseAddress, null, null);
      return mbb;
    } catch (final Exception e) {
      throw new RuntimeException(
          "Could not create Dummy MappedByteBuffer instance: " + e.getClass());
    }
  }

  /**
   * madvise is a system call made by load0 native method
   */
  private void madvise() throws RuntimeException {
    try {
      final Method method = MappedByteBuffer.class.getDeclaredMethod("load0", long.class, long.class);
      method.setAccessible(true);
      method.invoke(dummyMbbInstance_, nativeBaseAddress_, capacityBytes_);
    } catch (final Exception e) {
      throw new RuntimeException(
          String.format("Encountered %s exception while loading", e.getClass()));
    }
  }

  /**
   * Creates a mapping of the file on disk starting at position and of size length to pages in OS.
   * May throw OutOfMemory error if you have exhausted memory. Force garbage collection and
   * re-attempt.
   */
  private static final long map(final FileChannel fileChannel, final long position, final long len)
      throws RuntimeException {
    final int pagePosition = (int) (position % unsafe.pageSize());
    final long mapPosition = position - pagePosition;
    final long mapSize = len + pagePosition;

    try {
      final Method method =
          FileChannelImpl.class.getDeclaredMethod("map0", int.class, long.class, long.class);
      method.setAccessible(true);
      final long addr = (long) method.invoke(fileChannel, 1, mapPosition, mapSize);
      return addr;
    } catch (final Exception e) {
      throw new RuntimeException(
          String.format("Encountered %s exception while mapping", e.getClass()));
    }
  }

  private static final class Deallocator implements Runnable {
    private RandomAccessFile raf;
    private FileChannel fc;
    private long nativeBaseAdd;
    private long capBytes;

    private Deallocator(final RandomAccessFile randomAccessFile,
        final long nativeBaseAddress, final long capacityBytes) {
      assert (randomAccessFile != null);
      assert (nativeBaseAddress != 0);
      assert (capacityBytes != 0);
      raf = randomAccessFile;
      fc = randomAccessFile.getChannel();
      nativeBaseAdd = nativeBaseAddress;
      capBytes = capacityBytes;
    }

    /**
     * Removes existing mapping
     */
    private void unmap() throws RuntimeException {
      try {
        final Method method = FileChannelImpl.class.getDeclaredMethod("unmap0", long.class, long.class);
        method.setAccessible(true);
        method.invoke(fc, nativeBaseAdd, capBytes);
        raf.close();
      } catch (final Exception e) {
        throw new RuntimeException(
            String.format("Encountered %s exception while freeing memory", e.getClass()));
      }
    }

    @Override
    public void run() {
      if (fc != null) {
        unmap();
      }
      nativeBaseAdd = 0L;
    }
  } //End of class Deallocator

}
