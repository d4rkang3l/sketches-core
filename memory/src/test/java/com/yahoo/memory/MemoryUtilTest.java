/*
 * Copyright 2016, Yahoo! Inc. Licensed under the terms of the
 * Apache License 2.0. See LICENSE file at the project root for terms.
 */

package com.yahoo.memory;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

/**
 * @author Lee Rhodes
 */
public class MemoryUtilTest {

  @Test
  public void checkGoodCallback() {
    int k = 128;
    Memory mem = new NativeMemory(new byte[k]);
    mem.setMemoryRequest(new GoodMemoryManager());
    Memory newMem = MemoryUtil.memoryRequestHandler(mem, 2 * k, true);
    assertEquals(newMem.getCapacity(), 2 * k);
    Memory newMem2 = MemoryUtil.memoryRequestHandler(newMem, 4 * k, false);
    assertEquals(newMem2.getCapacity(), 4 * k);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void checkNoCallback() {
    int k = 128;
    Memory mem = new NativeMemory(new byte[k]);
    MemoryUtil.memoryRequestHandler(mem, 2 * k, true); //null MemoryRequest
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void checkBadCallback1() {
    int k = 128;
    Memory mem = new NativeMemory(new byte[k]);
    mem.setMemoryRequest(new BadMemoryManager1()); //returns a null Memory
    MemoryUtil.memoryRequestHandler(mem, 2 * k, true);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void checkBadCallback2() {
    int k = 128;
    Memory mem = new NativeMemory(new byte[k]);
    mem.setMemoryRequest(new BadMemoryManager2()); //returns Memory too small
    MemoryUtil.memoryRequestHandler(mem, 2 * k, false);
  }

  //////////////////////////////////////////////////////
  //////////////////////////////////////////////////////
  private static class GoodMemoryManager implements MemoryRequest { //Allocates what was requested
    NativeMemory last = null; //simple means of tracking the last Memory allocated

    @Override
    public Memory request(long capacityBytes) {
      last = new NativeMemory(new byte[(int)capacityBytes]);
      last.setMemoryRequest(this);
      println("\nReqCap: "+capacityBytes + ", Granted: "+last.getCapacity());
      return last;
    }

    @Override
    public Memory request(Memory origMem, long copyToBytes, long capacityBytes) {
      Memory newMem = request(capacityBytes);
      NativeMemory.copy(origMem, 0, newMem, 0, copyToBytes);
      println("\nOldCap: " + origMem.getCapacity() + ", ReqCap: " + capacityBytes
          + ", Granted: "+ newMem.getCapacity());
      return newMem;
    }

    @Override
    public void free(Memory mem) {
      if (mem instanceof AllocMemory) {
        println("\nmem Freed bytes : " + mem.getCapacity());
        ((AllocMemory)mem).freeMemory();
      } else if (mem instanceof MemoryRegion){
        println("\nThe original MemoryRegion can be reassigned.");
      }
    }

    @Override
    public void free(Memory memToFree, Memory newMem) {
      if (memToFree instanceof AllocMemory) {
        println("\nmemToFree  Freed bytes: " + memToFree.getCapacity());
        println("newMem Allocated bytes: " + newMem.getCapacity());
        ((AllocMemory)memToFree).freeMemory();
      } else if (memToFree instanceof MemoryRegion){
        println("\nThe original MemoryRegion can be reassigned.");
      }
    }

  } //end class GoodMemoryManager

  //////////////////////////////////////////////////////
  //////////////////////////////////////////////////////
  private static class BadMemoryManager1 implements MemoryRequest { //returns a null Memory

    @Override
    public Memory request(long capacityBytes) {
      return null;
    }

    @Override
    public Memory request(Memory origMem, long copyToBytes, long capacityBytes) {
      // not used.
      return null;
    }

    @Override
    public void free(Memory mem) {
      // not used.
    }

    @Override
    public void free(Memory memToFree, Memory newMem) {
      // not used.
    }

  } //end class BadMemoryManager1

  private static class BadMemoryManager2 implements MemoryRequest { //Allocates too small
    NativeMemory last = null; //simple means of tracking the last Memory allocated

    @Override
    public Memory request(long capacityBytes) {
      last = new NativeMemory(new byte[(int)capacityBytes - 1]); //Too Small
      last.setMemoryRequest(this);
      println("\nReqCap: "+capacityBytes + ", Granted: "+last.getCapacity());
      return last;
    }

    @Override
    public Memory request(Memory origMem, long copyToBytes, long capacityBytes) {
      Memory newMem = request(capacityBytes);
      NativeMemory.copy(origMem, 0, newMem, 0, copyToBytes);
      println("\nOldCap: " + origMem.getCapacity() + ", ReqCap: " + capacityBytes
          + ", Granted: "+ newMem.getCapacity());
      return newMem;
    }

    @Override
    public void free(Memory mem) {
      // not used.
    }

    @Override
    public void free(Memory memToFree, Memory newMem) {
      // not used.
    }

  } //end class BadMemoryManager2

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void checkBoundsTest() {
    UnsafeUtil.checkBounds(999, 2, 1000);
  }

  @SuppressWarnings("unused")
  @Test
  public void checkNullEmptyArrays() {
    try { boolean[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new boolean[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { byte[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new byte[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { char[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new char[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { short[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new short[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { int[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new int[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { long[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new long[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { float[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new float[0]); fail(); }
    catch (IllegalArgumentException e) {}

    try { double[] arr = null; new NativeMemory(arr); fail(); }
    catch (IllegalArgumentException e) {}
    try { new NativeMemory(new double[0]); fail(); }
    catch (IllegalArgumentException e) {}
  }

  @Test
  public void printlnTest() {
    println("PRINTING: "+this.getClass().getName());
  }

  /**
   * @param s value to print
   */
  static void println(String s) {
    System.out.println(s); //disable here
  }

}
